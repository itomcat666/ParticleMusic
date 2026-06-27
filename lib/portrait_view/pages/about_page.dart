part of '../../layer/about_layer.dart';

extension _AboutPage on _AboutLayerState {
  Widget pageView(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      backgroundColor: Colors.transparent,
      body: Column(
        children: [
          customAppBar(context),
          SizedBox(height: 10),
          Expanded(child: content()),
        ],
      ),
    );
  }

  PreferredSizeWidget customAppBar(BuildContext context) {
    return AppBar(
      automaticallyImplyLeading: false,
      leading: customAppBarLeading(context, label: 'settings'),
      backgroundColor: Colors.transparent,
      systemOverlayStyle: mainPageThemeNotifier.value == .dark ? .light : .dark,
      scrolledUnderElevation: 0,
      title: Text(AppLocalizations.of(context).about),
      centerTitle: true,
    );
  }
}
