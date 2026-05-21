import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';
import 'package:uuid/uuid.dart';

import '../app_actions/app_action_runtime.dart';
import '../models/chat_action.dart';
import '../services/chatbot_service.dart';
import '../theme/season_theme.dart';

class ChatFloatingButton extends StatefulWidget {
  const ChatFloatingButton({super.key});

  @override
  State<ChatFloatingButton> createState() => _ChatFloatingButtonState();
}

class _ChatFloatingButtonState extends State<ChatFloatingButton> {
  static const MethodChannel _speechChannel = MethodChannel(
    'flower_app/speech',
  );
  static const List<String> _examplePrompts = [
    '이번 달에 볼 만한 꽃 추천해줘',
    '분홍색 꽃인데 이름이 뭘까?',
    '장미 키우는 법 알려줘',
    '벚꽃 명소 지도에서 보여줘',
    '수국 후기 찾아줘',
  ];
  static final List<_FloatingChatMessage> _messages = [];
  static final String _sessionId = const Uuid().v4();

  final TextEditingController _controller = TextEditingController();
  final ChatbotService _chatbotService = ChatbotService();
  bool _showComposer = false;
  bool _showHistory = false;
  bool _isSending = false;
  bool _isListening = false;
  StreamSubscription<ChatbotStreamEvent>? _streamSubscription;
  CancelToken? _cancelToken;
  bool _cancelledByUser = false;
  bool _finalAnswerReceived = false;
  bool _doneReceived = false;
  final Set<String> _dispatchedActionKeys = <String>{};

  @override
  void dispose() {
    _cancelToken?.cancel('disposed');
    unawaited(_streamSubscription?.cancel());
    _controller.dispose();
    super.dispose();
  }

  Future<void> _sendMessage(String rawText) async {
    final text = rawText.trim();
    if (text.isEmpty || _isSending || _isListening) return;

    _controller.clear();
    FocusScope.of(context).unfocus();

    _cancelledByUser = false;
    _finalAnswerReceived = false;
    _doneReceived = false;
    _dispatchedActionKeys.clear();
    _cancelToken = CancelToken();

    setState(() {
      _messages.add(_FloatingChatMessage.user(text));
      _messages.add(_FloatingChatMessage.bot('AI가 요청을 확인하고 있어요.'));
      _isSending = true;
      _showHistory = true;
    });

    final position = await _getCurrentPositionOrNull();

    _streamSubscription = _chatbotService
        .streamMessage(
          message: text,
          sessionId: _sessionId,
          lat: position?.latitude ?? 37.5665,
          lng: position?.longitude ?? 126.9780,
          cancelToken: _cancelToken,
        )
        .listen(
          _handleStreamEvent,
          onError: _handleStreamError,
          onDone: _handleStreamDone,
          cancelOnError: false,
        );
  }

  void _handleStreamEvent(ChatbotStreamEvent event) {
    if (!mounted || _cancelledByUser) return;

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
          unawaited(AppActionRuntime.execute(context, actions));
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
        _finishStream();
        break;
      case 'ERROR':
        _handleStreamError(
          event.message.isEmpty ? '챗봇 처리 중 오류가 발생했습니다.' : event.message,
        );
        break;
      default:
        if (event.message.isNotEmpty) {
          _upsertBotMessage(event.message);
        }
    }
  }

  void _handleStreamError(Object error) {
    if (!mounted || _cancelledByUser) return;
    _replaceLastBotMessage('응답을 가져오지 못했습니다.');
    _finishStream();
  }

  void _handleStreamDone() {
    if (!mounted || _cancelledByUser) return;
    if (!_isSending || _doneReceived) return;
    if (!_finalAnswerReceived) {
      _replaceLastBotMessage('응답을 마무리하지 못했습니다. 다시 시도해 주세요.');
    }
    _finishStream();
  }

  void _finishStream() {
    if (!mounted) return;
    _cancelToken = null;
    _streamSubscription = null;
    setState(() => _isSending = false);
  }

  Future<void> _stopStream() async {
    _cancelledByUser = true;
    _cancelToken?.cancel('stopped by user');
    await _streamSubscription?.cancel();
    if (!mounted) return;
    _cancelToken = null;
    _streamSubscription = null;
    setState(() {
      _isSending = false;
      if (_messages.isNotEmpty && !_messages.last.isUser) {
        _messages.removeLast();
      }
    });
  }

  void _upsertBotMessage(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty || !mounted) return;

    setState(() {
      if (_messages.isNotEmpty && !_messages.last.isUser) {
        _messages[_messages.length - 1] = _FloatingChatMessage.bot(trimmed);
      } else {
        _messages.add(_FloatingChatMessage.bot(trimmed));
      }
      _showHistory = true;
    });
  }

  String _actionKey(ChatAction action) {
    return '${action.type}:${action.target}:${action.params}';
  }

  List<ChatAction> _singleAction(ChatAction? action) {
    return action == null ? <ChatAction>[] : <ChatAction>[action];
  }

  void _replaceLastBotMessage(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty || !mounted) return;

    setState(() {
      if (_messages.isNotEmpty && !_messages.last.isUser) {
        _messages[_messages.length - 1] = _FloatingChatMessage.bot(trimmed);
      } else {
        _messages.add(_FloatingChatMessage.bot(trimmed));
      }
      _showHistory = true;
    });
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

  Future<void> _listenAndSend() async {
    if (_isSending || _isListening) return;

    FocusScope.of(context).unfocus();

    try {
      final hasPermission = await _ensureMicrophonePermission();
      if (!mounted || !hasPermission) return;

      setState(() => _isListening = true);
      final spokenText =
          (await _speechChannel.invokeMethod<String>('listen'))?.trim() ?? '';
      if (!mounted) return;
      setState(() => _isListening = false);

      if (spokenText.isEmpty) {
        _showSnackBar('음성을 인식하지 못했습니다.');
        return;
      }

      _controller.text = spokenText;
      await _sendMessage(spokenText);
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _isListening = false);
      _showSnackBar(_speechErrorMessage(error));
    } catch (_) {
      if (!mounted) return;
      setState(() => _isListening = false);
      _showSnackBar('음성 입력을 사용할 수 없습니다.');
    }
  }

  Future<Position?> _getCurrentPositionOrNull() async {
    try {
      final permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return null;
      }
      return await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
        ),
      ).timeout(const Duration(seconds: 3));
    } catch (_) {
      return null;
    }
  }

  Future<bool> _ensureMicrophonePermission() async {
    final hasPermission =
        await _speechChannel.invokeMethod<bool>('hasRecordAudioPermission') ??
        false;
    if (hasPermission) return true;

    final granted =
        await _speechChannel.invokeMethod<bool>(
          'requestRecordAudioPermission',
        ) ??
        false;
    if (granted) return true;

    if (mounted) {
      _showSnackBar('마이크 권한을 허용해야 음성 입력을 사용할 수 있습니다.');
    }
    return false;
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  String _speechErrorMessage(PlatformException error) {
    switch (error.code) {
      case 'permission_denied':
        return '마이크 권한을 허용해야 음성 입력을 사용할 수 있습니다.';
      case 'unavailable':
      case 'MissingPluginException':
        return '이 기기에서는 음성 입력을 사용할 수 없습니다.';
      case 'empty':
        return '음성을 인식하지 못했습니다.';
      case 'cancelled':
        return '음성 입력이 취소되었습니다.';
      default:
        return '음성 입력 중 문제가 발생했습니다.';
    }
  }

  void _closeChatOverlay() {
    FocusScope.of(context).unfocus();
    setState(() {
      _showHistory = false;
      _showComposer = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    final size = MediaQuery.sizeOf(context);
    final dockWidth = (size.width - 24).clamp(296.0, 420.0);

    final child = !_showComposer
        ? FloatingActionButton(
            heroTag: 'global-chat-floating-button',
            tooltip: '챗봇',
            backgroundColor: colors.primary,
            foregroundColor: Colors.white,
            elevation: 8,
            shape: const CircleBorder(),
            onPressed: () {
              setState(() {
                _showComposer = true;
                _showHistory = true;
              });
            },
            child: const Icon(Icons.chat_bubble_outline, size: 26),
          )
        : SizedBox(
            width: dockWidth,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (_showHistory) ...[
                  _buildHistoryPanel(colors),
                  const SizedBox(height: 8),
                ],
                _buildInputDock(colors),
              ],
            ),
          );

    return PopScope(
      canPop: !_showComposer,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _showComposer) {
          _closeChatOverlay();
        }
      },
      child: child,
    );
  }

  Widget _buildHistoryPanel(SeasonColors colors) {
    final size = MediaQuery.sizeOf(context);
    final maxHeight = (size.height * 0.52).clamp(260.0, 520.0);

    return Container(
      constraints: BoxConstraints(maxHeight: maxHeight),
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.82),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: colors.primary.withValues(alpha: 0.14)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.12),
            blurRadius: 18,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(Icons.chat_bubble_outline, color: colors.primary, size: 18),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  '챗봇 대화',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13),
                ),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.close, size: 18),
                onPressed: _closeChatOverlay,
              ),
            ],
          ),
          const SizedBox(height: 4),
          if (_messages.isEmpty)
            _buildExamplePrompts(colors)
          else
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                reverse: true,
                itemCount: _messages.length,
                itemBuilder: (context, index) {
                  final message = _messages[_messages.length - 1 - index];
                  return _buildBubble(message, colors);
                },
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildExamplePrompts(SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(0, 8, 0, 6),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: _examplePrompts
            .map(
              (prompt) => ActionChip(
                label: Text(prompt, style: const TextStyle(fontSize: 11)),
                visualDensity: VisualDensity.compact,
                backgroundColor: colors.primary.withValues(alpha: 0.08),
                side: BorderSide(color: colors.primary.withValues(alpha: 0.16)),
                onPressed: _isSending || _isListening
                    ? null
                    : () => _sendMessage(prompt),
              ),
            )
            .toList(),
      ),
    );
  }

  Widget _buildInputDock(SeasonColors colors) {
    final micColor = _isListening ? Colors.redAccent : colors.primary;

    return Container(
      height: 52,
      padding: const EdgeInsets.only(left: 6, right: 4),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(26),
        border: Border.all(color: colors.primary.withValues(alpha: 0.16)),
        boxShadow: [
          BoxShadow(
            color: colors.primary.withValues(alpha: 0.18),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: Row(
        children: [
          IconButton(
            tooltip: '챗봇 대화 내역',
            icon: Icon(
              _showHistory ? Icons.expand_more : Icons.chat_bubble_outline,
              color: colors.primary,
            ),
            onPressed: () => setState(() => _showHistory = !_showHistory),
          ),
          Expanded(
            child: TextField(
              controller: _controller,
              enabled: !_isSending && !_isListening,
              textInputAction: TextInputAction.send,
              decoration: InputDecoration(
                hintText: _isListening
                    ? '말씀해주세요'
                    : _isSending
                    ? '응답을 기다리고 있어요'
                    : '챗봇에게 메시지 보내기',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: _sendMessage,
            ),
          ),
          IconButton(
            tooltip: _isListening ? '듣는 중' : '음성 입력',
            icon: Icon(
              _isListening ? Icons.mic : Icons.mic_none_rounded,
              color: micColor,
            ),
            onPressed: _isSending || _isListening ? null : _listenAndSend,
          ),
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: _isSending ? colors.primary : Colors.transparent,
              borderRadius: BorderRadius.circular(_isSending ? 10 : 20),
            ),
            child: IconButton(
              tooltip: _isSending ? '중지' : '전송',
              icon: Icon(
                _isSending ? Icons.stop_rounded : Icons.send_rounded,
                color: _isSending ? Colors.white : colors.primary,
              ),
              onPressed: _isListening
                  ? null
                  : _isSending
                  ? _stopStream
                  : () => _sendMessage(_controller.text),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBubble(_FloatingChatMessage message, SeasonColors colors) {
    final isUser = message.isUser;
    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 8),
        constraints: const BoxConstraints(maxWidth: 250),
        decoration: BoxDecoration(
          color: isUser ? colors.primary : Colors.white.withValues(alpha: 0.92),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Text(
          message.text,
          style: TextStyle(
            color: isUser ? Colors.white : const Color(0xFF2D2D2D),
            fontSize: 13,
            height: 1.35,
          ),
        ),
      ),
    );
  }
}

class _FloatingChatMessage {
  const _FloatingChatMessage._({required this.text, required this.isUser});

  factory _FloatingChatMessage.user(String text) =>
      _FloatingChatMessage._(text: text, isUser: true);

  factory _FloatingChatMessage.bot(String text) =>
      _FloatingChatMessage._(text: text, isUser: false);

  final String text;
  final bool isUser;
}
