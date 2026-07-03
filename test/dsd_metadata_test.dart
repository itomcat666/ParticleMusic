import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:sylvakru/base/services/dsd_metadata.dart';

// 手工构造 KB 级 DSF/DFF 头部验证解析（不使用版权音频）
void main() {
  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('dsd_metadata_test');
  });

  tearDown(() async {
    await tempDir.delete(recursive: true);
  });

  Future<String> writeFile(String name, List<int> bytes) async {
    final file = File('${tempDir.path}/$name');
    await file.writeAsBytes(bytes);
    return file.path;
  }

  test('DSF 头部解析出速率/时长，尾部 ID3v2.3 文本帧生效', () async {
    final builder = BytesBuilder();
    const sampleRate = 2822400;
    const channels = 2;
    const sampleCount = 2822400 * 2; // 每通道 2 秒
    final audioBytes = Uint8List(64); // 音频体不参与解析，给个占位
    final id3 = _buildId3v23({
      'TIT2': 'DSD 测试曲',
      'TPE1': '测试艺人',
      'TALB': '测试专辑',
      'TRCK': '3/12',
      'TYER': '2020',
    });
    final metadataOffset = 28 + 52 + 12 + audioBytes.length;

    builder.add('DSD '.codeUnits);
    builder.add(_longLe(28));
    builder.add(_longLe(metadataOffset + id3.length));
    builder.add(_longLe(metadataOffset));
    builder.add('fmt '.codeUnits);
    builder.add(_longLe(52));
    builder.add(_intLe(1)); // formatVersion
    builder.add(_intLe(0)); // formatId
    builder.add(_intLe(2)); // channelType
    builder.add(_intLe(channels));
    builder.add(_intLe(sampleRate));
    builder.add(_intLe(1)); // bitsPerSample
    builder.add(_longLe(sampleCount));
    builder.add(_intLe(4096)); // blockSizePerChannel
    builder.add(_intLe(0)); // reserved
    builder.add('data'.codeUnits);
    builder.add(_longLe(12 + audioBytes.length));
    builder.add(audioBytes);
    builder.add(id3);

    final path = await writeFile('sample.dsf', builder.toBytes());
    final metadata = await readDsdMetadata(path);

    expect(metadata, isNotNull);
    expect(metadata!.format, 'dsf');
    expect(metadata.samplerate, sampleRate);
    expect(metadata.duration, const Duration(seconds: 2));
    expect(metadata.title, 'DSD 测试曲');
    expect(metadata.artist, '测试艺人');
    expect(metadata.album, '测试专辑');
    expect(metadata.track, 3);
    expect(metadata.year, 2020);
  });

  test('DFF 头部解析出速率/声道/时长', () async {
    const sampleRate = 5644800;
    const channels = 2;
    // 每通道 1 秒 = 705600 字节
    final audio = Uint8List(sampleRate ~/ 8 * channels);

    final prop = BytesBuilder();
    prop.add('SND '.codeUnits);
    prop.add('FS  '.codeUnits);
    prop.add(_longBe(4));
    prop.add(_intBe(sampleRate));
    prop.add('CHNL'.codeUnits);
    prop.add(_longBe(2 + channels * 4));
    prop.add(_shortBe(channels));
    prop.add('SLFTSRGT'.codeUnits);
    final propBytes = prop.toBytes();

    final body = BytesBuilder();
    body.add('DSD '.codeUnits);
    body.add('FVER'.codeUnits);
    body.add(_longBe(4));
    body.add(_intBe(0x01050000));
    body.add('PROP'.codeUnits);
    body.add(_longBe(propBytes.length));
    body.add(propBytes);
    body.add('DSD '.codeUnits);
    body.add(_longBe(audio.length));
    body.add(audio);
    final bodyBytes = body.toBytes();

    final builder = BytesBuilder();
    builder.add('FRM8'.codeUnits);
    builder.add(_longBe(bodyBytes.length));
    builder.add(bodyBytes);

    final path = await writeFile('sample.dff', builder.toBytes());
    final metadata = await readDsdMetadata(path);

    expect(metadata, isNotNull);
    expect(metadata!.format, 'dff');
    expect(metadata.samplerate, sampleRate);
    expect(metadata.duration, const Duration(seconds: 1));
  });

  test('非 DSD 文件返回 null', () async {
    final path = await writeFile('not_dsd.dsf', 'RIFF0000WAVE'.codeUnits);
    expect(await readDsdMetadata(path), isNull);
  });
}

Uint8List _buildId3v23(Map<String, String> textFrames) {
  final frames = BytesBuilder();
  textFrames.forEach((id, value) {
    // UTF-8 编码（encoding byte = 3）
    final encoded = <int>[3, ...utf8.encode(value)];
    frames.add(id.codeUnits);
    frames.add(_intBe(encoded.length));
    frames.add([0, 0]); // flags
    frames.add(encoded);
  });
  final frameBytes = frames.toBytes();

  final builder = BytesBuilder();
  builder.add('ID3'.codeUnits);
  builder.add([3, 0, 0]); // v2.3, flags=0
  builder.add(_syncsafe(frameBytes.length));
  builder.add(frameBytes);
  return builder.toBytes();
}

List<int> _syncsafe(int value) => [
  (value >> 21) & 0x7f,
  (value >> 14) & 0x7f,
  (value >> 7) & 0x7f,
  value & 0x7f,
];

List<int> _intLe(int value) => [
  value & 0xff,
  (value >> 8) & 0xff,
  (value >> 16) & 0xff,
  (value >> 24) & 0xff,
];

List<int> _longLe(int value) => [
  for (var index = 0; index < 8; index++) (value >> (index * 8)) & 0xff,
];

List<int> _intBe(int value) => [
  (value >> 24) & 0xff,
  (value >> 16) & 0xff,
  (value >> 8) & 0xff,
  value & 0xff,
];

List<int> _shortBe(int value) => [(value >> 8) & 0xff, value & 0xff];

List<int> _longBe(int value) => [
  for (var index = 7; index >= 0; index--) (value >> (index * 8)) & 0xff,
];
