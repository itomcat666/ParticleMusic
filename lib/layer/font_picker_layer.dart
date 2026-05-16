import 'package:flutter/material.dart';
import 'package:particle_music/base/app.dart';
import 'package:particle_music/landscape_view/panels/font_picker_panel.dart';
import 'package:particle_music/portrait_view/pages/font_picker_page.dart';

class FontPickerLayer extends StatelessWidget {
  const FontPickerLayer({super.key});

  @override
  Widget build(BuildContext context) {
    return OrientationBuilder(
      builder: (context, orientation) {
        if (isMobile && orientation == Orientation.portrait) {
          return FontPickerPage();
        } else {
          return FontPickerPanel();
        }
      },
    );
  }
}
