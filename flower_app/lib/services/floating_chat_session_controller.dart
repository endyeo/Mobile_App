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

  static const Duration actionDispatchDelay = Duration(milliseconds: 650);

  final ChatbotService _chatbotService;
  final List<FloatingChatMessage> _messages = <FloatingChatMessage>[];
  final List<ChatAction> _pendingActions = <ChatAction>[];
  final Set<String> _pendingActionKeys = <String>{};
  final String _sessionId = const Uuid().v4();

  StreamSubscription<ChatbotStreamEvent>? _streamSubscription;
  CancelToken? _cancelToken;
  Timer? _actionDispatchTimer;
  ChatActionHandler? _activeActionHandler;
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
    _activeActionHandler = onActions;
    _clearPendingActions();
    _cancelToken = CancelToken();

    _messages
      ..add(FloatingChatMessage.user(text))
      ..add(FloatingChatMessage.botStreaming('질문을 이해하는 중...'));
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
          (event) => _handleStreamEvent(requestId, event),
          onError: (error) => _handleStreamError(requestId, error),
          onDone: () => _handleStreamDone(requestId),
          cancelOnError: false,
        );
  }

  Future<void> stopStream() async {
    _cancelledByUser = true;
    _clearPendingActions();
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

  void _handleStreamEvent(String requestId, ChatbotStreamEvent event) {
    if (!_isCurrentRequest(requestId, event.requestId) || _cancelledByUser) {
      return;
    }

    switch (event.type) {
      case 'CONNECTED':
        break;
      case 'STATUS':
      case 'CONTEXT_PLANNED':
        final message = _statusProgressMessage(event);
        if (message != null) {
          _appendProgressStep(message);
        }
        break;
      case 'FINAL_ANSWER':
        _finalAnswerReceived = true;
        _replaceLastBotMessage(event.response?.reply ?? event.message);
        _schedulePendingActionDispatch();
        break;
      case 'ACTION':
        final actions = event.actions.isNotEmpty
            ? event.actions
            : _singleAction(event.action);
        _appendProgressStep(_actionProgressMessage(actions));
        _storePendingActions(actions);
        if (_finalAnswerReceived) {
          _schedulePendingActionDispatch();
        }
        break;
      case 'TOOL_RESULT':
        final result = event.toolResult;
        if (result != null) {
          _appendProgressStep(_toolResultMessage(result));
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
          _appendProgressStep(event.message);
        }
    }
  }

  void _handleServerError(String requestId, String message) {
    if (!_isCurrentRequest(requestId, null) || _cancelledByUser) return;
    _clearPendingActions();
    if (_finalAnswerReceived || _doneReceived) {
      _finishStream(requestId);
      return;
    }
    _replaceLastBotMessage(
      message.trim().isEmpty ? '챗봇 처리 중 오류가 발생했습니다.' : message.trim(),
      streaming: false,
    );
    _finishStream(requestId);
  }

  void _handleStreamError(String requestId, Object error) {
    if (!_isCurrentRequest(requestId, null) || _cancelledByUser) return;
    _clearPendingActions();
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
      _clearPendingActions();
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
    if (!_finalAnswerReceived) {
      _activeActionHandler = null;
    }
    _notify();
  }

  bool _isCurrentRequest(String requestId, String? eventRequestId) {
    if (_activeRequestId != requestId) return false;
    final eventId = eventRequestId?.trim() ?? '';
    return eventId.isEmpty || eventId == requestId;
  }

  Future<void> _cancelActiveStreamForReplacement() async {
    _clearPendingActions();
    _cancelToken?.cancel('replaced by new request');
    await _streamSubscription?.cancel();
    _cancelToken = null;
    _streamSubscription = null;
    _activeRequestId = null;
    _isSending = false;
    _activeActionHandler = null;
    _notify();
  }

  void _replaceLastBotMessage(String text, {bool streaming = false}) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    if (_messages.isNotEmpty && !_messages.last.isUser) {
      _messages[_messages.length - 1] = _messages.last.copyWith(
        text: trimmed,
        isStreaming: streaming,
      );
    } else {
      _messages.add(FloatingChatMessage.bot(trimmed));
    }
    _showHistory = true;
    _notify();
  }

  void _appendProgressStep(String text, {bool notify = true}) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return;

    if (_messages.isEmpty || _messages.last.isUser) {
      _messages.add(FloatingChatMessage.botStreaming(trimmed));
    } else {
      final message = _messages.last;
      final steps = List<String>.from(message.progressSteps);
      if (steps.isEmpty || steps.last != trimmed) {
        steps.add(trimmed);
      }
      _messages[_messages.length - 1] = message.copyWith(
        currentStatus: trimmed,
        progressSteps: steps.length > 8
            ? steps.sublist(steps.length - 8)
            : steps,
      );
    }
    _showHistory = true;
    if (notify) _notify();
  }

  void toggleProcessExpanded(FloatingChatMessage message) {
    final index = _messages.indexOf(message);
    if (index < 0 || _messages[index].isUser) return;
    _messages[index] = _messages[index].copyWith(
      isProcessExpanded: !_messages[index].isProcessExpanded,
    );
    _notify();
  }

  String _actionKey(ChatAction action) {
    return '${action.type}:${action.target}:${action.params}';
  }

  void _storePendingActions(List<ChatAction> actions) {
    for (final action in actions) {
      final actionKey = _actionKey(action);
      if (_pendingActionKeys.add(actionKey)) {
        _pendingActions.add(action);
      }
    }
  }

  void _schedulePendingActionDispatch() {
    if (!_finalAnswerReceived ||
        _pendingActions.isEmpty ||
        _activeActionHandler == null) {
      return;
    }
    _actionDispatchTimer?.cancel();
    _actionDispatchTimer = Timer(actionDispatchDelay, () {
      final actions = List<ChatAction>.unmodifiable(_pendingActions);
      final handler = _activeActionHandler;
      _pendingActions.clear();
      _pendingActionKeys.clear();
      _activeActionHandler = null;
      if (actions.isNotEmpty && handler != null) {
        unawaited(handler(actions));
      }
    });
  }

  void _clearPendingActions() {
    _actionDispatchTimer?.cancel();
    _actionDispatchTimer = null;
    _pendingActions.clear();
    _pendingActionKeys.clear();
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
    if (result.tool.startsWith('festival.')) {
      return '축제 정보를 확인했어요.';
    }
    return '필요한 정보를 확인했어요.';
  }

  String? _statusProgressMessage(ChatbotStreamEvent event) {
    return switch (event.stage) {
      'PLAN' => '필요한 작업을 고르는 중...',
      'SEARCH' =>
        event.message.trim().isEmpty ? '필요한 정보를 찾는 중...' : event.message.trim(),
      _ => null,
    };
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
    _clearPendingActions();
    _cancelToken?.cancel('disposed');
    unawaited(_streamSubscription?.cancel());
    super.dispose();
  }
}

class FloatingChatMessage {
  const FloatingChatMessage._({
    required this.text,
    required this.isUser,
    this.progressSteps = const <String>[],
    this.currentStatus = '',
    this.isStreaming = false,
    this.isProcessExpanded = false,
  });

  factory FloatingChatMessage.user(String text) =>
      FloatingChatMessage._(text: text, isUser: true);

  factory FloatingChatMessage.bot(String text) =>
      FloatingChatMessage._(text: text, isUser: false);

  factory FloatingChatMessage.botStreaming(String status) =>
      FloatingChatMessage._(
        text: '',
        isUser: false,
        currentStatus: status,
        isStreaming: true,
      );

  final String text;
  final bool isUser;
  final List<String> progressSteps;
  final String currentStatus;
  final bool isStreaming;
  final bool isProcessExpanded;

  FloatingChatMessage copyWith({
    String? text,
    List<String>? progressSteps,
    String? currentStatus,
    bool? isStreaming,
    bool? isProcessExpanded,
  }) {
    return FloatingChatMessage._(
      text: text ?? this.text,
      isUser: isUser,
      progressSteps: progressSteps ?? this.progressSteps,
      currentStatus: currentStatus ?? this.currentStatus,
      isStreaming: isStreaming ?? this.isStreaming,
      isProcessExpanded: isProcessExpanded ?? this.isProcessExpanded,
    );
  }
}
