import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:uuid/uuid.dart';

import '../models/chat_action.dart';
import 'chatbot_service.dart';

typedef ChatActionHandler = Future<void> Function(List<ChatAction> actions);

class FloatingChatSessionController extends ChangeNotifier {
  FloatingChatSessionController({ChatbotService? chatbotService})
    : _chatbotService = chatbotService ?? ChatbotService();

  final ChatbotService _chatbotService;
  final List<FloatingChatMessage> _messages = <FloatingChatMessage>[];
  final Set<String> _dispatchedActionKeys = <String>{};
  final String _sessionId = const Uuid().v4();

  StreamSubscription<ChatbotStreamEvent>? _streamSubscription;
  CancelToken? _cancelToken;
  bool _cancelledByUser = false;
  bool _finalAnswerReceived = false;
  bool _doneReceived = false;
  String? _activeRequestId;
  bool _isSending = false;
  bool _showComposer = false;
  bool _showHistory = false;
  String _draftText = '';
  bool _disposed = false;

  List<FloatingChatMessage> get messages => List.unmodifiable(_messages);
  bool get isSending => _isSending;
  bool get showComposer => _showComposer;
  bool get showHistory => _showHistory;
  String get draftText => _draftText;

  void updateDraft(String value) {
    _draftText = value;
  }

  void openComposer() {
    _showComposer = true;
    _showHistory = true;
    _notify();
  }

  void setHistoryVisible(bool visible) {
    _showHistory = visible;
    _notify();
  }

  void closeOverlay() {
    _showHistory = false;
    _showComposer = false;
    _notify();
  }

  Future<void> sendMessage({
    required String rawText,
    required double lat,
    required double lng,
    ChatActionHandler? onActions,
  }) async {
    final text = rawText.trim();
    if (text.isEmpty) return;

    if (_isSending) {
      await _cancelActiveStreamForReplacement();
    }

    final requestId = const Uuid().v4();
    _activeRequestId = requestId;
    _draftText = '';
    _cancelledByUser = false;
    _finalAnswerReceived = false;
    _doneReceived = false;
    _dispatchedActionKeys.clear();
    _cancelToken = CancelToken();

    _messages
      ..add(FloatingChatMessage.user(text))
      ..add(FloatingChatMessage.bot('AI가 요청을 확인하고 있어요.'));
    _isSending = true;
    _showHistory = true;
    _notify();

    _streamSubscription = _chatbotService
        .streamMessage(
          message: text,
          sessionId: _sessionId,
          requestId: requestId,
          lat: lat,
          lng: lng,
          cancelToken: _cancelToken,
        )
        .listen(
          (event) => _handleStreamEvent(requestId, event, onActions),
          onError: (error) => _handleStreamError(requestId, error),
          onDone: () => _handleStreamDone(requestId),
          cancelOnError: false,
        );
  }

  Future<void> stopStream() async {
    _cancelledByUser = true;
    _cancelToken?.cancel('stopped by user');
    await _streamSubscription?.cancel();
    _cancelToken = null;
    _streamSubscription = null;
    _activeRequestId = null;
    _isSending = false;
    if (_messages.isNotEmpty && !_messages.last.isUser) {
      _messages.removeLast();
    }
    _notify();
  }

  void _handleStreamEvent(
    String requestId,
    ChatbotStreamEvent event,
    ChatActionHandler? onActions,
  ) {
    if (!_isCurrentRequest(requestId, event.requestId) || _cancelledByUser) {
      return;
    }

    switch (event.type) {
      case 'CONNECTED':
        break;
      case 'STATUS':
      case 'CONTEXT_PLANNED':
        _upsertBotMessage(event.message);
        break;
      case 'FINAL_ANSWER':
        _finalAnswerReceived = true;
        _replaceLastBotMessage(event.response?.reply ?? event.message);
        break;
      case 'ACTION':
        final actions = event.actions.isNotEmpty
            ? event.actions
            : _singleAction(event.action);
        _upsertBotMessage(_actionProgressMessage(actions));
        final actionKey = actions.map(_actionKey).join('|');
        if (actions.isNotEmpty && _dispatchedActionKeys.add(actionKey)) {
          unawaited(onActions?.call(actions));
        }
        break;
      case 'TOOL_RESULT':
        final result = event.toolResult;
        if (result != null) {
          _upsertBotMessage(_toolResultMessage(result));
        }
        break;
      case 'DONE':
        _doneReceived = true;
        _finishStream(requestId);
        break;
      case 'ERROR':
        _handleServerError(requestId, event.message);
        break;
      default:
        if (event.message.isNotEmpty) {
          _upsertBotMessage(event.message);
        }
    }
  }

  void _handleServerError(String requestId, String message) {
    if (!_isCurrentRequest(requestId, null) || _cancelledByUser) return;
    if (_finalAnswerReceived || _doneReceived) {
      _finishStream(requestId);
      return;
    }
    _replaceLastBotMessage(
      message.trim().isEmpty ? '챗봇 처리 중 오류가 발생했습니다.' : message.trim(),
    );
    _finishStream(requestId);
  }

  void _handleStreamError(String requestId, Object error) {
    if (!_isCurrentRequest(requestId, null) || _cancelledByUser) return;
    if (_finalAnswerReceived || _doneReceived) {
      _finishStream(requestId);
      return;
    }
    _replaceLastBotMessage(_streamErrorMessage(error));
    _finishStream(requestId);
  }

  void _handleStreamDone(String requestId) {
    if (!_isCurrentRequest(requestId, null) || _cancelledByUser) return;
    if (!_isSending || _doneReceived) return;
    if (!_finalAnswerReceived) {
      _replaceLastBotMessage('연결이 예상보다 일찍 종료되었습니다. 다시 시도해 주세요.');
    }
    _finishStream(requestId);
  }

  void _finishStream(String requestId) {
    if (!_isCurrentRequest(requestId, null)) return;
    _cancelToken = null;
    _streamSubscription = null;
    _activeRequestId = null;
    _isSending = false;
    _notify();
  }

  bool _isCurrentRequest(String requestId, String? eventRequestId) {
    if (_activeRequestId != requestId) return false;
    final eventId = eventRequestId?.trim() ?? '';
    return eventId.isEmpty || eventId == requestId;
  }

  Future<void> _cancelActiveStreamForReplacement() async {
    _cancelToken?.cancel('replaced by new request');
    await _streamSubscription?.cancel();
    _cancelToken = null;
    _streamSubscription = null;
    _activeRequestId = null;
    _isSending = false;
    _notify();
  }

  void _upsertBotMessage(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    if (_messages.isNotEmpty && !_messages.last.isUser) {
      _messages[_messages.length - 1] = FloatingChatMessage.bot(trimmed);
    } else {
      _messages.add(FloatingChatMessage.bot(trimmed));
    }
    _showHistory = true;
    _notify();
  }

  void _replaceLastBotMessage(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    if (_messages.isNotEmpty && !_messages.last.isUser) {
      _messages[_messages.length - 1] = FloatingChatMessage.bot(trimmed);
    } else {
      _messages.add(FloatingChatMessage.bot(trimmed));
    }
    _showHistory = true;
    _notify();
  }

  String _actionKey(ChatAction action) {
    return '${action.type}:${action.target}:${action.params}';
  }

  List<ChatAction> _singleAction(ChatAction? action) {
    return action == null ? <ChatAction>[] : <ChatAction>[action];
  }

  String _actionProgressMessage(List<ChatAction> actions) {
    if (actions.any((action) => action.target == 'MAP')) {
      return '지도 화면 이동을 준비하고 있어요.';
    }
    if (actions.any(
      (action) => action.target?.startsWith('COMMUNITY') ?? false,
    )) {
      return '커뮤니티 화면 이동을 준비하고 있어요.';
    }
    return '앱 화면 이동을 준비하고 있어요.';
  }

  String _toolResultMessage(ToolResult result) {
    if (result.tool.startsWith('flower.')) {
      return '꽃 정보를 확인했어요.';
    }
    if (result.tool.startsWith('community.')) {
      return '커뮤니티 정보를 확인했어요.';
    }
    return '필요한 정보를 확인했어요.';
  }

  String _streamErrorMessage(Object error) {
    if (error is ChatbotStreamException) {
      debugPrint('챗봇 스트림 오류: ${error.debugMessage}');
      return error.message;
    }
    debugPrint('챗봇 스트림 오류: $error');
    return '챗봇 연결 중 문제가 발생했습니다. 다시 시도해 주세요.';
  }

  void _notify() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _cancelToken?.cancel('disposed');
    unawaited(_streamSubscription?.cancel());
    super.dispose();
  }
}

class FloatingChatMessage {
  const FloatingChatMessage._({required this.text, required this.isUser});

  factory FloatingChatMessage.user(String text) =>
      FloatingChatMessage._(text: text, isUser: true);

  factory FloatingChatMessage.bot(String text) =>
      FloatingChatMessage._(text: text, isUser: false);

  final String text;
  final bool isUser;
}
