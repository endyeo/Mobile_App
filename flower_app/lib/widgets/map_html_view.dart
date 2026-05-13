import 'package:flutter/widgets.dart';

import 'map_html_view_stub.dart'
    if (dart.library.html) 'map_html_view_web.dart';

Widget buildMapHtmlView({
  required String viewType,
  required String html,
  bool isInteractive = true,
}) {
  return buildPlatformMapHtmlView(
    viewType: viewType,
    html: html,
    isInteractive: isInteractive,
  );
}
