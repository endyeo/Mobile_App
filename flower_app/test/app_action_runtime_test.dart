import 'package:flower_app/app_actions/app_action_runtime.dart';
import 'package:flower_app/models/chat_action.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'classifies map route actions by MAP target and MAP_START_ROUTE type',
    () {
      const action = ChatAction(
        type: 'MAP_START_ROUTE',
        target: 'MAP',
        params: {'flowerId': 7, 'mode': 'transit'},
      );

      expect(AppActionRuntime.isMapActionForTest(action), isTrue);
      expect(AppActionRuntime.isScreenActionForTest(action), isFalse);
      expect(AppActionRuntime.stringParamForTest(action, 'mode'), 'transit');
      expect(AppActionRuntime.intParamForTest(action, 'flowerId'), 7);
    },
  );

  test('classifies MAP typed actions as map actions', () {
    const action = ChatAction(
      type: 'MAP_SET_SEARCH_QUERY',
      target: 'MAP',
      params: {'query': '벚꽃'},
    );

    expect(AppActionRuntime.isMapActionForTest(action), isTrue);
    expect(AppActionRuntime.stringParamForTest(action, 'query'), '벚꽃');
  });

  test('classifies compose navigation as screen action', () {
    const action = ChatAction(type: 'NAVIGATE', target: 'COMMUNITY_COMPOSE');

    expect(AppActionRuntime.isMapActionForTest(action), isFalse);
    expect(AppActionRuntime.isScreenActionForTest(action), isTrue);
  });

  test('reads flower book id from flowerBookId param', () {
    const action = ChatAction(
      type: 'NAVIGATE',
      target: 'FLOWER_BOOK',
      params: {'flowerBookId': '12', 'query': '장미'},
    );

    expect(AppActionRuntime.isScreenActionForTest(action), isTrue);
    expect(AppActionRuntime.intParamForTest(action, 'flowerBookId'), 12);
    expect(AppActionRuntime.stringParamForTest(action, 'query'), '장미');
  });
}
