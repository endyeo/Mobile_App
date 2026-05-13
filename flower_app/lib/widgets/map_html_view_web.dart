// ignore_for_file: avoid_web_libraries_in_flutter

import 'dart:html' as dom;
import 'dart:ui_web' as ui_web;

import 'package:flutter/widgets.dart';

final Set<String> _registeredViewTypes = <String>{};

Widget buildPlatformMapHtmlView({
  required String viewType,
  required String html,
  bool isInteractive = true,
}) {
  if (_registeredViewTypes.add(viewType)) {
    ui_web.platformViewRegistry.registerViewFactory(viewType, (int viewId) {
      final frame = dom.IFrameElement()
        ..src = html
        ..style.border = '0'
        ..style.width = '100%'
        ..style.height = '100%'
        ..style.pointerEvents = isInteractive ? 'auto' : 'none'
        ..allow = 'geolocation';
      return frame;
    });
  }
  return HtmlElementView(viewType: viewType);
}
