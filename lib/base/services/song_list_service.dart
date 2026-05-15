import 'dart:convert';
import 'dart:io';

import 'package:particle_music/base/data/library.dart';
import 'package:particle_music/base/my_audio_metadata.dart';

Future<void> loadSongList(
  File songIdListFile,
  List<MyAudioMetadata> destList,
) async {
  final List<dynamic> songIdList = jsonDecode(
    await songIdListFile.readAsString(),
  );
  for (final id in songIdList) {
    destList.add(library.id2Song[id]!);
  }
}
