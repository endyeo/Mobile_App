import 'package:flutter/material.dart';

import '../models/chat_action.dart';
import '../screens/community_feed_screen.dart';
import '../screens/flower_book_page.dart';
import '../screens/kakao_map_screen.dart';
import '../screens/pedometer_screen.dart';
import '../screens/saved_page.dart';

class AppActionRuntime {
  const AppActionRuntime._();

  static Future<void> execute(
    BuildContext context,
    List<ChatAction> actions,
  ) async {
    try {
      final mapActions = actions.where(_isMapAction).toList();
      if (mapActions.isNotEmpty) {
        await _push(context, KakaoMapScreen(initialActions: mapActions));
        return;
      }

      final screenActions = actions.where(_isScreenAction).toList();
      final action = screenActions.isEmpty ? null : screenActions.first;
      if (action == null) return;

      final screen = _screenFor(action);
      if (screen == null) {
        if (!context.mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${action.target ?? action.type} 화면은 아직 준비 중입니다.')),
        );
        return;
      }

      await _push(context, screen);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('화면 이동에 실패했습니다.')),
        );
      }
    }
  }

  static Future<void> _push(BuildContext context, Widget screen) async {
    if (!context.mounted) return;

    if (screen is KakaoMapScreen) {
      await Navigator.push(
        context,
        PageRouteBuilder(
          pageBuilder: (_, __, ___) => screen,
          transitionDuration: Duration.zero,
          reverseTransitionDuration: Duration.zero,
        ),
      );
      return;
    }

    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => screen),
    );
  }

  static bool _isMapAction(ChatAction action) {
    return action.target == 'MAP' ||
        action.type == 'MAP_SET_SEARCH_QUERY' ||
        action.type == 'MAP_SHOW_FLOWER' ||
        action.type == 'MAP_OPEN_FLOWER_PREVIEW';
  }

  static bool _isScreenAction(ChatAction action) {
    return action.type == 'NAVIGATE' || action.type == 'PREPARE_DRAFT';
  }

  static Widget? _screenFor(ChatAction action) {
    switch ((action.target ?? '').toUpperCase()) {
      case 'COMMUNITY':
        return const CommunityFeedScreen();
      case 'WALK':
      case 'PEDOMETER':
        return const PedometerScreen();
      case 'FLOWER':
      case 'FLOWER_BOOK':
      case 'BOOK':
        return const FlowerBookPage();
      case 'SAVED':
      case 'BOOKMARK':
      case 'BOOKMARKS':
        return const SavedPage();
      default:
        return null;
    }
  }
}
