import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:particle_music/base/services/bookmark_service.dart';
import 'package:particle_music/base/app.dart';
import 'package:particle_music/base/services/song_list_service.dart';
import 'package:particle_music/base/utils/path.dart';
import 'package:particle_music/base/services/logger.dart';
import 'package:particle_music/base/services/webdav_client.dart';
import 'package:particle_music/base/widgets/manage_music_folders.dart';
import 'package:particle_music/layer/layers_manager.dart';
import 'package:particle_music/base/data/library.dart';
import 'package:particle_music/base/my_audio_metadata.dart';
import 'package:path/path.dart';
import 'package:uuid/uuid.dart';

final Set<String> _loftySupportedExts = {
  '.mp2',
  '.mp3',
  '.flac',
  '.m4a',
  '.m4r',
  '.mp4',
  '.aac',
  '.wav',
  '.aiff',
  '.aif',
  '.ogg',
  '.opus',
  '.ape',
  '.mpc',
  '.wv',
  '.spx',
};

class Folder {
  final String id;
  final String path;
  late Directory? _dir;
  bool isWebdav;
  late File _songIdListFile;

  List<MyAudioMetadata> songList = [];

  Map<String, DateTime> pathAndModified = {};

  ValueNotifier<int> sortTypeNotifier = ValueNotifier(0);

  final changeNotifier = ValueNotifier(0);

  Folder(this.id, this.path, String songIdListPath, {this.isWebdav = false}) {
    if (!isWebdav) {
      _dir = Directory(path);
    }
    _songIdListFile = File(songIdListPath);
    if (!_songIdListFile.existsSync()) {
      _songIdListFile.writeAsStringSync('[]');
    }
  }

  static Future<Folder> fromLocal(Map<String, dynamic> map) async {
    String id = map['id'] as String;
    String path = id;
    String songIdListPath = map['songIdListPath'] as String;
    if (Platform.isIOS) {
      if (id.startsWith('Particle Music')) {
        path =
            '${appDocsDir.parent.path}/${id.replaceFirst('Particle Music', 'Documents')}';
      } else {
        path = await BookmarkService.getUrlById(id) ?? '';
        library.setIOSFileProviderStorageIfNeed(path);
      }
      songIdListPath = "${getFolderConfigPath(.local)}/$songIdListPath";
    }

    return Folder(id, path, songIdListPath);
  }

  static Future<Folder> fromWebdav(Map<String, dynamic> map) async {
    String id = map['id'] as String;
    String path = id;

    String songIdListPath = map['songIdListPath'] as String;

    return Folder(id, path, songIdListPath, isWebdav: true);
  }

  static Future<Folder> createLocal(String id) async {
    final uuid = Uuid();
    final songIdListPath = '${getFolderConfigPath(.local)}/${uuid.v4()}.json';

    String path = id;
    if (Platform.isIOS) {
      if (id.startsWith('Particle Music')) {
        path =
            '${appDocsDir.parent.path}/${id.replaceFirst('Particle Music', 'Documents')}';
      } else {
        path = library.iosFileProviderStorage! + id;
        if (!await BookmarkService.saveDirectoryAndActive(id, path)) {
          path = '';
        }
      }
    }

    return Folder(id, path, songIdListPath);
  }

  static Future<Folder> createWebdav(String id) async {
    final uuid = Uuid();
    final songIdListPath = '${getFolderConfigPath(.webdav)}/${uuid.v4()}.json';

    String path = id;

    return Folder(id, path, songIdListPath, isWebdav: true);
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'songIdListPath': Platform.isIOS
          ? _songIdListFile.path.split('folder_config/').last
          : _songIdListFile.path,
    };
  }

  Future<void> setFileAndModified() async {
    if (isWebdav) {
      await for (final file in webdavClient!.listStream(
        path,
        recursive: recursiveScanNotifier.value,
      )) {
        if (!file.isDirectory) {
          final ext = extension(file.path).toLowerCase();

          if (!_loftySupportedExts.contains(ext)) {
            continue;
          }
          pathAndModified.addEntries([MapEntry(file.path, file.modified!)]);
        }
      }
    } else {
      await for (final file in _dir!.list(
        recursive: recursiveScanNotifier.value,
      )) {
        if (file is File) {
          final ext = extension(file.path).toLowerCase();

          if (!_loftySupportedExts.contains(ext)) {
            continue;
          }
          pathAndModified.addEntries([
            MapEntry(file.path, (await file.stat()).modified),
          ]);
        }
      }
    }
  }

  void clearPathAndModified() {
    pathAndModified.clear();
  }

  Future<void> load() async {
    await loadSongList(_songIdListFile, songList);
  }

  Future<void> _saveSongIdList() async {
    await _songIdListFile.writeAsString(
      jsonEncode(songList.map((e) => e.id).toList()),
    );
  }

  void shuffle() {
    songList.shuffle();
    update();
  }

  void update() {
    changeNotifier.value++;
    layersManager.updateBackground();
    _saveSongIdList();
  }

  void delete() {
    try {
      _songIdListFile.deleteSync();
    } catch (e) {
      logger.output(e.toString());
    }
  }

  Future<void> sync() async {
    final List<dynamic> songIdList = jsonDecode(
      await _songIdListFile.readAsString(),
    );

    for (final id in songIdList) {
      final song = library.id2Song[id];

      if (song != null) {
        songList.add(song);
        String path = song.path!;
        if (isWebdav) {
          path = Uri.parse(path).path;
          path = Uri.decodeFull(path);
        }

        pathAndModified.remove(path);
      }
    }

    for (final entry in pathAndModified.entries) {
      String path = entry.key;
      String id = path;

      if (isWebdav) {
        id = Uri.parse(webdavClient!.baseUrl).resolve(path).toString();
        id = Uri.decodeFull(id);
      } else if (Platform.isIOS) {
        id = convertIOSPath(path);
      }

      final song = library.id2Song[id];
      if (song != null) {
        songList.add(song);
      }
    }

    update();
  }
}
