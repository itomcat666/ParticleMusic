import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';

/// lofty 不支持 DSD 容器，这里手工解析 DSF/DFF 的音频参数：
/// - DSF（Sony，小端）：`DSD ` → `fmt `（速率/声道/采样数）→ 尾部 ID3v2 标签；
/// - DFF（Philips，大端 IFF）：`FRM8` 里遍历 `FS  `/`CHNL`/`DSD ` 取参数，无标准标签。
/// samplerate 存真实 DSD 速率（如 2822400），UI 层据此显示 DSD64/128/…。
/// 返回 null 表示不是可识别的 DSD 文件。
Future<AudioMetadata?> readDsdMetadata(String path) async {
  final file = File(path);
  if (!await file.exists()) {
    return null;
  }
  final input = await file.open();
  try {
    final magic = await input.read(4);
    if (magic.length < 4) {
      return null;
    }
    switch (String.fromCharCodes(magic)) {
      case 'DSD ':
        return await _readDsf(input);
      case 'FRM8':
        return await _readDff(input);
      default:
        return null;
    }
  } catch (_) {
    return null;
  } finally {
    await input.close();
  }
}

Future<AudioMetadata?> _readDsf(RandomAccessFile input) async {
  final dsdChunk = ByteData.sublistView(await input.read(24));
  if (dsdChunk.lengthInBytes < 24) {
    return null;
  }
  final dsdChunkSize = dsdChunk.getUint64(0, Endian.little);
  final metadataOffset = dsdChunk.getUint64(16, Endian.little);

  await input.setPosition(dsdChunkSize);
  final fmtChunk = ByteData.sublistView(await input.read(52));
  if (fmtChunk.lengthInBytes < 52 ||
      String.fromCharCodes(Uint8List.sublistView(fmtChunk, 0, 4)) != 'fmt ') {
    return null;
  }
  final channels = fmtChunk.getUint32(24, Endian.little);
  final sampleRate = fmtChunk.getUint32(28, Endian.little);
  final sampleCount = fmtChunk.getUint64(36, Endian.little);
  if (channels < 1 || channels > 6 || sampleRate <= 0) {
    return null;
  }

  final metadata = AudioMetadata(
    format: 'dsf',
    samplerate: sampleRate,
    bitrate: sampleRate * channels ~/ 1000,
    duration: Duration(microseconds: sampleCount * 1000000 ~/ sampleRate),
  );
  if (metadataOffset > 0) {
    await _applyId3v2(input, metadataOffset, metadata);
  }
  return metadata;
}

Future<AudioMetadata?> _readDff(RandomAccessFile input) async {
  final length = await input.length();
  final header = ByteData.sublistView(await input.read(12));
  if (header.lengthInBytes < 12 ||
      String.fromCharCodes(Uint8List.sublistView(header, 8, 12)) != 'DSD ') {
    return null;
  }
  final formEnd = (12 + header.getUint64(0, Endian.big)).clamp(0, length).toInt();

  var sampleRate = 0;
  var channels = 0;
  var dataBytes = 0;
  var offset = 16;
  while (offset + 12 <= formEnd) {
    await input.setPosition(offset);
    final chunkHeader = ByteData.sublistView(await input.read(12));
    if (chunkHeader.lengthInBytes < 12) {
      break;
    }
    final id = String.fromCharCodes(Uint8List.sublistView(chunkHeader, 0, 4));
    final chunkSize = chunkHeader.getUint64(4, Endian.big);
    switch (id) {
      case 'PROP':
        final propType = await input.read(4);
        if (String.fromCharCodes(propType) == 'SND ') {
          var propOffset = offset + 16;
          final propEnd = (offset + 12 + chunkSize).clamp(0, formEnd).toInt();
          while (propOffset + 12 <= propEnd) {
            await input.setPosition(propOffset);
            final subHeader = ByteData.sublistView(await input.read(12));
            if (subHeader.lengthInBytes < 12) {
              break;
            }
            final subId = String.fromCharCodes(
              Uint8List.sublistView(subHeader, 0, 4),
            );
            final subSize = subHeader.getUint64(4, Endian.big);
            if (subId == 'FS  ') {
              sampleRate = ByteData.sublistView(
                await input.read(4),
              ).getUint32(0, Endian.big);
            } else if (subId == 'CHNL') {
              channels = ByteData.sublistView(
                await input.read(2),
              ).getUint16(0, Endian.big);
            }
            // IFF chunk 按偶数字节对齐
            propOffset += 12 + subSize + (subSize & 1);
          }
        }
        break;
      // DST 压缩的数据块也先按大小算时长，播放时由引擎给出明确错误
      case 'DSD ':
      case 'DST ':
        dataBytes = chunkSize.clamp(0, length - offset - 12).toInt();
        break;
    }
    offset += 12 + chunkSize + (chunkSize & 1);
  }

  if (sampleRate <= 0 || channels < 1 || channels > 6) {
    return null;
  }
  return AudioMetadata(
    format: 'dff',
    samplerate: sampleRate,
    bitrate: sampleRate * channels ~/ 1000,
    duration: Duration(
      microseconds: dataBytes * 8 ~/ channels * 1000000 ~/ sampleRate,
    ),
  );
}

const _id3TextFrames = {
  'TIT2', 'TPE1', 'TALB', 'TPE2', 'TCON', 'TRCK', 'TPOS', 'TYER', 'TDRC',
};

/// DSF 尾部是标准 ID3v2（v2.3/v2.4），只取常用文本帧，封面与其余帧忽略。
Future<void> _applyId3v2(
  RandomAccessFile input,
  int offset,
  AudioMetadata metadata,
) async {
  await input.setPosition(offset);
  final header = await input.read(10);
  if (header.length < 10 ||
      String.fromCharCodes(header.sublist(0, 3)) != 'ID3') {
    return;
  }
  final major = header[3];
  if (major < 3) {
    return;
  }
  var remaining = _syncsafe(header, 6);
  while (remaining > 10) {
    final frameHeader = await input.read(10);
    if (frameHeader.length < 10 || frameHeader[0] == 0) {
      return;
    }
    final id = String.fromCharCodes(frameHeader.sublist(0, 4));
    // v2.4 帧大小是 syncsafe，v2.3 是普通大端
    final size = major >= 4
        ? _syncsafe(frameHeader, 4)
        : ByteData.sublistView(frameHeader).getUint32(4, Endian.big);
    if (size <= 0 || size > remaining) {
      return;
    }
    remaining -= 10 + size;
    if (!_id3TextFrames.contains(id) || size > 4096) {
      await input.setPosition(await input.position() + size);
      continue;
    }
    final text = _decodeId3Text(await input.read(size));
    if (text.isEmpty) {
      continue;
    }
    switch (id) {
      case 'TIT2':
        metadata.title = text;
        break;
      case 'TPE1':
        metadata.artist = text;
        break;
      case 'TALB':
        metadata.album = text;
        break;
      case 'TPE2':
        metadata.albumArtist = text;
        break;
      case 'TCON':
        metadata.genre = text;
        break;
      case 'TRCK':
        metadata.track = int.tryParse(text.split('/').first);
        break;
      case 'TPOS':
        metadata.disc = int.tryParse(text.split('/').first);
        break;
      case 'TYER':
      case 'TDRC':
        metadata.year ??= int.tryParse(
          text.length >= 4 ? text.substring(0, 4) : text,
        );
        break;
    }
  }
}

int _syncsafe(List<int> bytes, int offset) {
  return ((bytes[offset] & 0x7f) << 21) |
      ((bytes[offset + 1] & 0x7f) << 14) |
      ((bytes[offset + 2] & 0x7f) << 7) |
      (bytes[offset + 3] & 0x7f);
}

String _decodeId3Text(Uint8List body) {
  if (body.isEmpty) {
    return '';
  }
  final content = body.sublist(1);
  String text;
  switch (body[0]) {
    case 1: // UTF-16 带 BOM
      text = _decodeUtf16(content, null);
      break;
    case 2: // UTF-16BE 无 BOM
      text = _decodeUtf16(content, Endian.big);
      break;
    default: // 0=Latin-1、3=UTF-8，按 UTF-8 尽力解码
      text = String.fromCharCodes(content.where((byte) => byte != 0));
      try {
        text = const Utf8Decoder(allowMalformed: true).convert(
          content.takeWhile((byte) => byte != 0).toList(),
        );
      } catch (_) {}
      break;
  }
  return text.replaceAll('\x00', '').trim();
}

String _decodeUtf16(Uint8List bytes, Endian? endian) {
  if (bytes.length < 2) {
    return '';
  }
  var start = 0;
  var effective = endian ?? Endian.little;
  if (endian == null) {
    if (bytes[0] == 0xFF && bytes[1] == 0xFE) {
      effective = Endian.little;
      start = 2;
    } else if (bytes[0] == 0xFE && bytes[1] == 0xFF) {
      effective = Endian.big;
      start = 2;
    }
  }
  final units = <int>[];
  for (var index = start; index + 1 < bytes.length; index += 2) {
    final unit = effective == Endian.little
        ? bytes[index] | (bytes[index + 1] << 8)
        : (bytes[index] << 8) | bytes[index + 1];
    if (unit == 0) {
      break;
    }
    units.add(unit);
  }
  return String.fromCharCodes(units);
}
