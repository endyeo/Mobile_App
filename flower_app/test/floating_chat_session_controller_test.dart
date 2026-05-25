import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flower_app/services/chatbot_service.dart';
import 'package:flower_app/services/floating_chat_session_controller.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('closeOverlay does not cancel an active stream', () async {
    final streamController = StreamController<Uint8List>();
    final controller = FloatingChatSessionController(
      chatbotService: ChatbotService(
        client: Dio()..httpClientAdapter = _StreamAdapter(streamController),
      ),
    );

    await controller.sendMessage(rawText: '장미 알려줘', lat: 37.5, lng: 127.0);

    expect(controller.isSending, isTrue);
    expect(controller.showHistory, isTrue);
    expect(controller.messages.last.text, 'AI가 요청을 확인하고 있어요.');

    controller.closeOverlay();

    expect(controller.isSending, isTrue);
    expect(controller.showComposer, isFalse);
    expect(controller.showHistory, isFalse);

    await streamController.close();
    controller.dispose();
  });

  test('updates shared messages when the active stream completes', () async {
    final streamController = StreamController<Uint8List>();
    final controller = FloatingChatSessionController(
      chatbotService: ChatbotService(
        client: Dio()..httpClientAdapter = _StreamAdapter(streamController),
      ),
    );

    await controller.sendMessage(rawText: '수국 알려줘', lat: 37.5, lng: 127.0);
    streamController
      ..add(
        Uint8List.fromList(
          utf8.encode('''
event: FINAL_ANSWER
data: {"reply":"수국 답변"}

'''),
        ),
      )
      ..add(
        Uint8List.fromList(
          utf8.encode('''
event: DONE
data: {"reason":"completed"}

'''),
        ),
      );
    await streamController.close();
    await pumpEventQueue();

    expect(controller.isSending, isFalse);
    expect(controller.messages.last.text, '수국 답변');

    controller.dispose();
  });
}

class _StreamAdapter implements HttpClientAdapter {
  const _StreamAdapter(this.streamController);

  final StreamController<Uint8List> streamController;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody(
      streamController.stream,
      200,
      headers: {
        Headers.contentTypeHeader: ['text/event-stream'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
