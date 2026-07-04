import 'dart:async';

import 'package:flutter/material.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/base/services/usb_audio_service.dart';

/// 独占模式下按安卓物理音量键时弹出的悬浮音量窗口：显示当前音量并可手动拖动调节，
/// 静止约 2 秒后自动隐藏。系统音量条已被 MainActivity 拦截，改由本窗口反馈与操作。
/// 叠在 MaterialApp 之上（需 Stack 父级），只在收到物理音量键事件时显示。
class UsbExclusiveVolumeOverlay extends StatefulWidget {
  const UsbExclusiveVolumeOverlay({super.key});

  @override
  State<UsbExclusiveVolumeOverlay> createState() =>
      _UsbExclusiveVolumeOverlayState();
}

class _UsbExclusiveVolumeOverlayState extends State<UsbExclusiveVolumeOverlay> {
  bool _visible = false;
  Timer? _hideTimer;

  @override
  void initState() {
    super.initState();
    usbExclusiveVolumeKeyNotifier.addListener(_show);
  }

  @override
  void dispose() {
    usbExclusiveVolumeKeyNotifier.removeListener(_show);
    _hideTimer?.cancel();
    super.dispose();
  }

  // 显示窗口并重置自动隐藏计时；拖动滑条时也调用它保持窗口常驻。
  void _show() {
    _hideTimer?.cancel();
    _hideTimer = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _visible = false);
    });
    if (!_visible) {
      setState(() => _visible = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    // 适配窗口宽度：手机竖屏留边、横屏与桌面大窗封顶 360，始终水平居中顶部。
    final width = (media.size.width - 32).clamp(0.0, 360.0);
    return Positioned(
      top: media.padding.top + 16,
      left: 0,
      right: 0,
      child: IgnorePointer(
        ignoring: !_visible,
        child: AnimatedOpacity(
          opacity: _visible ? 1 : 0,
          duration: const Duration(milliseconds: 180),
          child: Center(child: SizedBox(width: width, child: _card())),
        ),
      ),
    );
  }

  Widget _card() {
    return ValueListenableBuilder<ThemeType>(
      valueListenable: mainPageThemeNotifier,
      builder: (context, theme, _) {
        final accent = iconColor.value;
        return ValueListenableBuilder<double>(
          valueListenable: volumeNotifier,
          builder: (context, volume, _) {
            final clamped = volume.clamp(0.0, 1.0);
            final percent = (clamped * 100).round();
            return DecoratedBox(
              decoration: BoxDecoration(
                color: menuColor.value,
                borderRadius: BorderRadius.circular(16),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withAlpha(
                      theme == ThemeType.dark ? 90 : 40,
                    ),
                    blurRadius: 18,
                    offset: const Offset(0, 6),
                  ),
                ],
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 6, 14, 6),
                child: Row(
                  children: [
                    Icon(
                      percent == 0
                          ? Icons.volume_off_rounded
                          : percent < 50
                          ? Icons.volume_down_rounded
                          : Icons.volume_up_rounded,
                      color: accent,
                    ),
                    const SizedBox(width: 6),
                    Expanded(
                      child: SliderTheme(
                        data: SliderTheme.of(context).copyWith(
                          activeTrackColor: accent,
                          inactiveTrackColor: accent.withAlpha(40),
                          thumbColor: accent,
                          overlayColor: accent.withAlpha(30),
                        ),
                        child: Slider(
                          value: clamped,
                          min: 0,
                          max: 1,
                          onChanged: (next) {
                            volumeNotifier.value = next;
                            audioHandler.setVolume(next);
                            _show();
                          },
                          onChangeEnd: (_) => audioHandler.savePlayState(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 4),
                    SizedBox(
                      width: 42,
                      child: Text(
                        '$percent%',
                        textAlign: TextAlign.end,
                        style: TextStyle(
                          color: textColor.value.withAlpha(200),
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
