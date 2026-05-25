import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flower_app/services/chatbot_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'streamMessage skips malformed SSE events and keeps valid events',
    () async {
      final dio = Dio()
        ..httpClientAdapter = _SseAdapter('''
event: STATUS
data: {"stage":"PLAN","message":"계획 중","requestId":"request-1"}

event: STATUS
data: not-json

event: FINAL_ANSWER
data: {"reply":"완료","request_id":"request-1"}

''');
      final service = ChatbotService(client: dio);

      final events = await service
          .streamMessage(
            message: '장미 알려줘',
            sessionId: 'session-1',
            requestId: 'request-1',
          )
          .toList();

      expect(events.map((event) => event.type), ['STATUS', 'FINAL_ANSWER']);
      expect(events.first.message, '계획 중');
      expect(events.last.response?.reply, '완료');
    },
  );

  test('ChatbotStreamException separates user and debug messages', () {
    final exception = ChatbotStreamException.fromDio(
      DioException(
        requestOptions: RequestOptions(path: '/chatbot/message/stream'),
        type: DioExceptionType.badResponse,
        response: Response<dynamic>(
          requestOptions: RequestOptions(path: '/chatbot/message/stream'),
          statusCode: 500,
          data: {
            'error': {'message': '사용자용 오류'},
          },
        ),
      ),
    );

    expect(exception.message, '사용자용 오류');
    expect(exception.debugMessage, contains('statusCode=500'));
    expect(exception.toString(), '사용자용 오류');
  });
}

class _SseAdapter implements HttpClientAdapter {
  _SseAdapter(this.body);

  final String body;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody(
      Stream<Uint8List>.value(Uint8List.fromList(utf8.encode(body))),
      200,
      headers: {
        Headers.contentTypeHeader: ['text/event-stream'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
