import 'dart:io';

import 'package:flutter/material.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/landscape_view/sidebar.dart';
import 'package:sylvakru/layer/layers_manager.dart';
import 'package:sylvakru/portrait_view/play_bar.dart';

final GlobalKey<ScaffoldState> portraitKey = GlobalKey();
bool isDrawerOpen = false;

class PortraitView extends StatefulWidget {
  const PortraitView({super.key});

  @override
  State<StatefulWidget> createState() => _PortraitViewState();
}

class _PortraitViewState extends State<PortraitView>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<Offset> _slideAnimation;

  late Animation<Offset> _bottomSlideAnimation;

  void slideBegin() {
    _controller.forward(from: 0);
  }

  @override
  void initState() {
    super.initState();

    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    _slideAnimation =
        Tween<Offset>(
          begin: Offset(Platform.isIOS ? 1.0 : -1.0, 0.0),
          end: Offset.zero,
        ).animate(
          CurvedAnimation(
            parent: _controller,
            curve: const Cubic(0.25, 0.10, 0.25, 1.0),
          ),
        );

    _bottomSlideAnimation =
        Tween<Offset>(begin: Offset.zero, end: Offset(-1 / 3, 0)).animate(
          CurvedAnimation(
            parent: _controller,
            curve: const Cubic(0.25, 0.10, 0.25, 1.0),
          ),
        );

    layersManager.switchNotifier.addListener(slideBegin);
    _controller.forward(from: 1);
  }

  @override
  void dispose() {
    layersManager.switchNotifier.removeListener(slideBegin);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: portraitKey,
      extendBodyBehindAppBar: true,
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      drawer: Platform.isAndroid ? myDrawer() : null,
      endDrawer: Platform.isIOS ? myDrawer() : null,
      onDrawerChanged: (isOpened) async {
        // ensure popscope gets correct drawer state
        if (!isOpened) {
          await Future.delayed(Duration(milliseconds: 250));
        }

        isDrawerOpen = isOpened;
      },
      body: Stack(
        children: [
          ValueListenableBuilder(
            valueListenable: layersManager.switchNotifier,
            builder: (context, _, _) {
              return GestureDetector(
                onHorizontalDragEnd: (details) {
                  final velocity = (details.primaryVelocity ?? 0);

                  if (Platform.isAndroid && velocity > 500) {
                    portraitKey.currentState?.openDrawer();
                  } else if (Platform.isIOS && velocity < -500) {
                    portraitKey.currentState?.openEndDrawer();
                  }
                },
                child: Stack(
                  children: [
                    ...layersManager.rootPageMap.values
                        .where((page) => page != layersManager.topRootPage)
                        .map((page) {
                          final visible = page == layersManager.bottomRootPage;
                          return Visibility(
                            visible: visible,
                            maintainState: true,
                            child: visible && Platform.isIOS
                                ? SlideTransition(
                                    position: _bottomSlideAnimation,
                                    child: page,
                                  )
                                : page,
                          );
                        }),

                    SlideTransition(
                      position: _slideAnimation,
                      child: layersManager.topRootPage,
                    ),
                  ],
                ),
              );
            },
          ),

          Positioned(left: 20, right: 20, bottom: 40, child: PlayBar()),
        ],
      ),
    );
  }

  Widget myDrawer() {
    return ValueListenableBuilder(
      valueListenable: layersManager.backgroundChangeNotifier,
      builder: (context, value, child) {
        return Drawer(
          backgroundColor: backgroundCoverArtColor,
          width: 220,
          child: Column(
            children: [
              ValueListenableBuilder(
                valueListenable: sidebarColor.valueNotifier,
                builder: (context, value, child) {
                  return Container(
                    color: value,
                    height: MediaQuery.of(context).padding.top,
                  );
                },
              ),
              Expanded(
                child: Sidebar(
                  closeDrawer: () {
                    Navigator.pop(context);
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
