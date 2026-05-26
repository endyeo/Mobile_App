import 'package:flutter/material.dart';

import '../models/chat_action.dart';
import '../screens/community_feed_screen.dart';
import '../screens/create_flower_spot_screen.dart';
import '../screens/flower_book_page.dart';
import '../screens/kakao_map_screen.dart';
import '../screens/pedometer_screen.dart';
import '../screens/saved_page.dart';

class AppActionRuntime {
  const AppActionRuntime._();

  @visibleForTesting
  static bool isMapActionForTest(ChatAction action) => _isMapAction(action);

  @visibleForTesting
  static bool isScreenActionForTest(ChatAction action) =>
      _isScreenAction(action);

  @visibleForTesting
  static String? stringParamForTest(ChatAction action, String key) =>
      _stringParam(action, key);

  @visibleForTesting
  static int? intParamForTest(ChatAction action, String key) =>
      _intParam(action, key);

  static Future<void> execute(
    BuildContext context,
    List<ChatAction> actions,
  ) async {
    try {
      final mapActions = actions.where(_isMapAction).toList();
      if (mapActions.isNotEmpty) {
        final activeMap = KakaoMapScreenState.activeState;
        if (_isInsideMapScreen(context) && activeMap != null) {
          final effectiveActions = mapActions
              .where((action) => action.type != 'NAVIGATE')
              .toList();
          if (effectiveActions.isEmpty) return;
          await activeMap.applyChatActions(effectiveActions);
          return;
        }
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
          SnackBar(
            content: Text('${action.target ?? action.type} 화면은 아직 준비 중입니다.'),
          ),
        );
        return;
      }

      await _push(context, screen);
    } catch (e) {
      if (context.mounted) {
        final target = actions.isEmpty
            ? '화면'
            : actions.first.target ?? actions.first.type;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('$target 이동에 실패했습니다.')));
      }
    }
  }

  static Future<void> _push(BuildContext context, Widget screen) async {
    if (!context.mounted) return;

    if (screen is KakaoMapScreen) {
      await Navigator.push(
        context,
        PageRouteBuilder(
          pageBuilder: (context, animation, secondaryAnimation) => screen,
          transitionDuration: Duration.zero,
          reverseTransitionDuration: Duration.zero,
        ),
      );
      return;
    }

    await Navigator.push(context, MaterialPageRoute(builder: (_) => screen));
  }

  static bool _isInsideMapScreen(BuildContext context) {
    return context.findAncestorWidgetOfExactType<KakaoMapScreen>() != null;
  }

  static bool _isMapAction(ChatAction action) {
    return action.target == 'MAP' ||
        action.type == 'MAP_SET_SEARCH_QUERY' ||
        action.type == 'MAP_SHOW_FLOWER' ||
        action.type == 'MAP_OPEN_FLOWER_PREVIEW' ||
        action.type == 'MAP_OPEN_ROUTE_CHOOSER' ||
        action.type == 'MAP_START_ROUTE';
  }

  static bool _isScreenAction(ChatAction action) {
    return action.type == 'NAVIGATE' || action.type == 'PREPARE_DRAFT';
  }

  static Widget? _screenFor(ChatAction action) {
    if (action.type == 'PREPARE_DRAFT' &&
        (action.target ?? '').toUpperCase() == 'COMMUNITY') {
      return const CreateFlowerSpotScreen();
    }

    switch ((action.target ?? '').toUpperCase()) {
      case 'COMMUNITY':
        return CommunityFeedScreen(initialQuery: _stringParam(action, 'query'));
      case 'COMMUNITY_COMPOSE':
        return const CreateFlowerSpotScreen();
      case 'WALK':
      case 'PEDOMETER':
        return const PedometerScreen();
      case 'FLOWER':
      case 'FLOWER_BOOK':
      case 'BOOK':
        return FlowerBookPage(
          initialQuery: _stringParam(action, 'query'),
          initialFlowerBookId: _intParam(action, 'flowerBookId'),
        );
      case 'SAVED':
      case 'BOOKMARK':
      case 'BOOKMARKS':
        return const SavedPage();
      default:
        return null;
    }
  }

  static String? _stringParam(ChatAction action, String key) {
    final value = action.params?[key];
    if (value == null) return null;
    final text = value.toString().trim();
    return text.isEmpty ? null : text;
  }

  static int? _intParam(ChatAction action, String key) {
    final value = action.params?[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value);
    return null;
  }
}
