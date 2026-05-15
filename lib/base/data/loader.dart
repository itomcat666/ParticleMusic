import 'dart:convert';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:particle_music/base/audio_handler.dart';
import 'package:particle_music/base/data/config.dart';
import 'package:particle_music/base/data/artist_album.dart';
import 'package:particle_music/base/services/bookmark_service.dart';
import 'package:particle_music/base/services/color_manager.dart';
import 'package:particle_music/base/app.dart';
import 'package:particle_music/base/data/history.dart';
import 'package:particle_music/layer/layers_manager.dart';
import 'package:particle_music/base/data/library.dart';
import 'package:particle_music/base/data/playlist.dart';
import 'package:particle_music/base/data/setting.dart';
import 'package:permission_handler/permission_handler.dart';

final ValueNotifier<int> loadedCountNotifier = ValueNotifier(0);

class Loader {
  static bool _syncing = false;

  static bool get syncing => _syncing;

  static final syncStateNotifier = ValueNotifier(0);

  static Future<void> init() async {
    if (Platform.isAndroid) {
      await Permission.storage.request();
      await Permission.audio.request();
    } else if (Platform.isIOS) {
      await BookmarkService.init();
    }

    _handleLegacyVersionData();

    await config.load();
    await setting.load();

    colorManager = ColorManager();
    colorManager.loadCustomColors();

    library = Library();
    await library.initAllFolders();

    await playlistManager.initAllPlaylists();

    audioHandler.initStateFiles();
  }

  static Future<void> load() async {
    await library.load();

    artistAlbumManager.classify();

    history.load();

    await playlistManager.load();

    await audioHandler.loadPlayQueueState();
    await audioHandler.loadPlayState();
    await audioHandler.loadEqualizerState();

    await layersManager.pushLayer('songs');
  }

  static Future<void> sync(int syncBitMask) async {
    _syncing = true;
    syncStateNotifier.value++;

    await audioHandler.clear();
    artistAlbumManager.clear();

    if ((syncBitMask & 1) == 1) {
      await library.sync(.local);
      history.sync(.local);
      await playlistManager.sync(.local);
    }

    if ((syncBitMask & 2) == 2) {
      await library.sync(.webdav);
      history.sync(.webdav);
      await playlistManager.sync(.webdav);
    }

    if ((syncBitMask & 4) == 4) {
      await library.sync(.navidrome);
      history.sync(.navidrome);
      await playlistManager.sync(.navidrome);
    }

    if ((syncBitMask & 8) == 8) {
      await library.sync(.emby);
      history.sync(.emby);
      await playlistManager.sync(.emby);
    }

    artistAlbumManager.classify();

    _syncing = false;
    syncStateNotifier.value++;
  }

  static void _handleLegacyVersionData() {
    File tmp = File('${appSupportDir.path}/version.json');
    if (tmp.existsSync()) {
      return;
    } else {
      tmp.writeAsStringSync(jsonEncode(versionNumber));
    }
  }
}
