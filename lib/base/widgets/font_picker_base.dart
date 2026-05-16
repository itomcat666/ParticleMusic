import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:just_font_scan/just_font_scan.dart';
import 'package:particle_music/base/app.dart';
import 'package:particle_music/base/data/library.dart';
import 'package:particle_music/base/data/setting.dart';
import 'package:particle_music/base/services/interaction.dart';
import 'package:particle_music/l10n/generated/app_localizations.dart';

abstract class FontPickerBase extends StatefulWidget {
  const FontPickerBase({super.key});
}

abstract class FontPickerBaseState extends State<FontPickerBase> {
  final textController = TextEditingController();
  final ValueNotifier<List<String>> fontsNotifier = ValueNotifier([]);
  List<String> allFonts = [];

  final previewText = "Music 音乐 123";

  @override
  void initState() {
    super.initState();
    reloadAllFonts();
    textController.addListener(update);
  }

  void reloadAllFonts() {
    allFonts.clear();
    allFonts.addAll(importedFonts);
    if (Platform.isWindows || Platform.isMacOS) {
      allFonts.addAll(JustFontScan.scan().map((e) => e.name).toList());
    }
    update();
  }

  void update() {
    fontsNotifier.value = allFonts.where((font) {
      return font.toLowerCase().contains(textController.text.toLowerCase());
    }).toList();
  }

  @override
  void dispose() {
    textController.dispose();
    super.dispose();
  }

  void resetFontAction() async {
    final l10n = AppLocalizations.of(context);

    if (await showConfirmDialog(context, l10n.reset)) {
      fontFamilyNotifier.value = null;
      setting.save();
      setState(() {});
    }
  }

  void addFontAction(BuildContext context) async {
    final l10n = AppLocalizations.of(context);

    final fileResult = await FilePicker.pickFiles(
      type: .custom,
      allowedExtensions: ['ttf', 'otf', 'ttc'],
      allowMultiple: true,
    );
    if (fileResult != null) {
      if (context.mounted) {
        final result = await getInputTextDialog(context, l10n.setFontName);
        if (result == '') {
          return;
        }
        final loader = FontLoader(result);

        for (final file in fileResult.files) {
          final bytes = await File(file.path!).readAsBytes();
          loader.addFont(Future.value(ByteData.view(bytes.buffer)));
        }

        await loader.load();

        if (importedFonts.contains(result)) {
          setState(() {});
        } else {
          importedFonts.add(result);
          allFonts.clear();
          reloadAllFonts();
        }

        library.addFonts(result, fileResult.files.map((e) => e.path!).toList());
      }
    }
  }
}
