part of '../../layer/playlists_layer.dart';

extension _PlaylistsPage on _PlaylistsLayerState {
  Widget pageView(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return Scaffold(
      backgroundColor: Colors.transparent,
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: customAppBarLeading(context),
        backgroundColor: Colors.transparent,
        systemOverlayStyle: mainPageThemeNotifier.value == .dark
            ? .light
            : .dark,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: Text(l10n.playlists),
        centerTitle: true,
      ),
      body: ValueListenableBuilder(
        valueListenable: playlistManager.updateNotifier,
        builder: (context, _, _) {
          return ListView.builder(
            itemCount: playlistManager.playlists.length + 1,
            itemBuilder: (_, index) {
              if (index == playlistManager.playlists.length) {
                return SizedBox(height: 70);
              }
              final playlist = playlistManager.getPlaylistByIndex(index);
              return ListTile(
                contentPadding: EdgeInsets.fromLTRB(20, 0, 20, 0),
                visualDensity: const VisualDensity(horizontal: 0, vertical: -1),

                leading: ValueListenableBuilder(
                  valueListenable: playlist.songListManager.changeNotifier,
                  builder: (_, _, _) {
                    final coverSong = playlist.getCoverSong();

                    return Hero(
                      tag:
                          (coverSong == null
                              ? playlist.songListManager.sourceTypeName
                              : coverSong.id) +
                          playlist.name,
                      curve: Curves.easeInOutCubic,
                      flightShuttleBuilder:
                          (
                            flightContext,
                            animation,
                            flightDirection,
                            fromHeroContext,
                            toHeroContext,
                          ) => FittedBox(child: toHeroContext.widget),
                      child: CoverArtWidget(
                        size: 50,
                        borderRadius: 5,
                        song: coverSong,
                      ),
                    );
                  },
                ),
                title: AutoSizeText(
                  index == 0 ? l10n.favorites : playlist.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  minFontSize: 15,
                  maxFontSize: 15,
                ),
                subtitle: ValueListenableBuilder(
                  valueListenable: playlist.songListManager.changeNotifier,
                  builder: (_, _, _) {
                    return Text(l10n.songCount(playlist.totalCount));
                  },
                ),
                onTap: () {
                  layersManager.pushDetail('playlists', playlist);
                },
              );
            },
          );
        },
      ),
    );
  }
}
