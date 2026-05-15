import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:particle_music/base/services/logger.dart';

EmbyClient? embyClient;

class EmbyClient {
  final String baseUrl;
  final String username;
  final String password;

  late final Dio dio;

  String? accessToken;
  String? userId;

  EmbyClient({
    required this.baseUrl,
    required this.username,
    required this.password,
  }) {
    dio = Dio(
      BaseOptions(
        baseUrl: _normalizeBaseUrl(baseUrl),
        headers: {
          'Content-Type': 'application/json',
          'X-Emby-Authorization':
              'MediaBrowser Client="ParticleMusic", Device="Flutter", DeviceId="particle_music", Version="1.0.0"',
        },
      ),
    );

    _applyInterceptor();
  }

  static String _normalizeBaseUrl(String url) {
    if (url.endsWith('/')) {
      return url.substring(0, url.length - 1);
    }
    return url;
  }

  void _applyInterceptor() {
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          if (accessToken != null) {
            options.headers['X-Emby-Token'] = accessToken;
          }

          handler.next(options);
        },
      ),
    );
  }

  /// Login
  Future<void> login() async {
    final response = await dio.post(
      '/Users/AuthenticateByName',
      data: {'Username': username, 'Pw': password},
    );

    accessToken = response.data['AccessToken'];
    userId = response.data['User']['Id'];
  }

  Future<bool> ping() async {
    try {
      final response = await dio.get('/System/Info/Public');

      return response.statusCode == 200;
    } catch (e) {
      return false;
    }
  }

  /// Get all libraries
  Future<List<dynamic>> getLibraries() async {
    final response = await dio.get('/Users/$userId/Views');

    return response.data['Items'];
  }

  /// Get music libraries only
  Future<List<dynamic>> getMusicLibraries() async {
    final libraries = await getLibraries();

    return libraries.where((e) {
      return e['CollectionType'] == 'music';
    }).toList();
  }

  Stream<List<Map<String, dynamic>>> getAllSongs({int limit = 50}) async* {
    final libraries = await getMusicLibraries();

    if (libraries.isEmpty) return;

    final libraryId = libraries.first['Id'];

    int startIndex = 0;
    const limit = 50;

    while (true) {
      final response = await dio.get(
        '/Users/$userId/Items',
        queryParameters: {
          'ParentId': libraryId,
          'Recursive': true,
          'IncludeItemTypes': 'Audio',

          'StartIndex': startIndex,
          'Limit': limit,

          'Fields':
              'Id,Name,Album,Artists,AlbumArtist,RunTimeTicks,Genres,ProductionYear,IndexNumber,ParentIndexNumber,MediaSources,UserData',

          'EnableImages': false,
        },
      );

      final items = (response.data['Items'] as List)
          .cast<Map<String, dynamic>>();

      if (items.isEmpty) break;

      yield items;

      startIndex += limit;

      logger.output('Fetched $startIndex songs...');
    }
  }

  /// Audio stream URL
  String audioUrl(String songId) {
    return '${dio.options.baseUrl}/Audio/$songId/stream'
        '?UserId=$userId&api_key=$accessToken&static=true';
  }

  Future<Uint8List> getPictureBytes(String itemId) async {
    final response = await dio.get<List<int>>(
      '/Items/$itemId/Images/Primary',
      options: Options(responseType: ResponseType.bytes),
    );

    return Uint8List.fromList(response.data!);
  }

  Future<void> downloadSong({
    required String itemId,
    required String savePath,
  }) async {
    await dio.download(
      '/Items/$itemId/Download',
      savePath,
      queryParameters: {'api_key': accessToken},
    );
  }

  Future<List<String>> getFavoriteSongIds() async {
    final res = await dio.get(
      '/Users/$userId/Items',
      queryParameters: {
        'IncludeItemTypes': 'Audio',
        'Recursive': true,
        'Filters': 'IsFavorite',
      },
    );

    return (res.data['Items'] as List).map((e) => e['Id'].toString()).toList();
  }

  Future<void> clearFavorites() async {
    final ids = await getFavoriteSongIds();

    for (final id in ids) {
      await dio.delete('/Users/$userId/FavoriteItems/$id');
    }
  }

  Future<void> rebuildFavorites(List<String> songIds) async {
    await clearFavorites();

    for (final id in songIds) {
      await dio.post('/Users/$userId/FavoriteItems/$id');
    }
  }

  Future<List<dynamic>> getPlaylists() async {
    final res = await dio.get(
      '/Users/$userId/Items',
      queryParameters: {'IncludeItemTypes': 'Playlist', 'Recursive': true},
    );

    return res.data['Items'];
  }

  Future<List<String>> getPlaylistItems(String playlistId) async {
    final res = await dio.get('/Playlists/$playlistId/Items');

    return (res.data['Items'] as List).map((e) => e['Id'].toString()).toList();
  }

  Future<String?> createPlaylist({
    required String name,
    required List<String> songIds,
  }) async {
    final res = await dio.post(
      '/Playlists',
      queryParameters: {
        'Name': name,
        'Ids': songIds.join(','),
        'MediaType': 'Audio',
      },
    );

    return res.data['Id'];
  }

  Future<void> deletePlaylist(String playlistId) async {
    await dio.delete('/Items/$playlistId');
  }
}
