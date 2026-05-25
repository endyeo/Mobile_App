import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';

import '../app_actions/app_action_runtime.dart';
import '../services/floating_chat_session_controller.dart';
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
  static final FloatingChatSessionController _session =
      FloatingChatSessionController();

  late final TextEditingController _controller;
  bool _isListening = false;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: _session.draftText);
    _session.addListener(_handleSessionChanged);
  }

  @override
  void dispose() {
    _session.updateDraft(_controller.text);
    _session.removeListener(_handleSessionChanged);
    _controller.dispose();
    super.dispose();
  }

  void _handleSessionChanged() {
    if (!mounted) return;
    if (_controller.text != _session.draftText) {
      _controller.text = _session.draftText;
    }
    setState(() {});
  }

  Future<void> _sendMessage(String rawText) async {
    final text = rawText.trim();
    if (text.isEmpty || _isListening) return;

    _controller.clear();
    _session.updateDraft('');
    FocusScope.of(context).unfocus();

    final position = await _getCurrentPositionOrNull();

    await _session.sendMessage(
      rawText: text,
      lat: position?.latitude ?? 37.5665,
      lng: position?.longitude ?? 126.9780,
      onActions: (actions) => AppActionRuntime.execute(context, actions),
    );
  }

  Future<void> _listenAndSend() async {
    if (_session.isSending || _isListening) return;

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
      _session.updateDraft(spokenText);
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
    _session.closeOverlay();
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    final size = MediaQuery.sizeOf(context);
    final dockWidth = (size.width - 24).clamp(296.0, 420.0);

    final child = !_session.showComposer
        ? FloatingActionButton(
            heroTag: 'global-chat-floating-button',
            tooltip: '챗봇',
            backgroundColor: colors.primary,
            foregroundColor: Colors.white,
            elevation: 8,
            shape: const CircleBorder(),
            onPressed: () {
              _session.openComposer();
            },
            child: const Icon(Icons.smart_toy_outlined, size: 26),
          )
        : SizedBox(
            width: dockWidth,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                if (_session.showHistory) ...[
                  _buildHistoryPanel(colors),
                  const SizedBox(height: 8),
                ],
                _buildInputDock(colors),
              ],
            ),
          );

    return PopScope(
      canPop: !_session.showComposer,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop && _session.showComposer) {
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
              Icon(Icons.smart_toy_outlined, color: colors.primary, size: 18),
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
          if (_session.messages.isEmpty)
            _buildExamplePrompts(colors)
          else
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                reverse: true,
                itemCount: _session.messages.length,
                itemBuilder: (context, index) {
                  final message =
                      _session.messages[_session.messages.length - 1 - index];
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
                onPressed: _session.isSending || _isListening
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
              _session.showHistory
                  ? Icons.expand_more
                  : Icons.smart_toy_outlined,
              color: colors.primary,
            ),
            onPressed: () => _session.setHistoryVisible(!_session.showHistory),
          ),
          Expanded(
            child: TextField(
              controller: _controller,
              enabled: !_session.isSending && !_isListening,
              textInputAction: TextInputAction.send,
              decoration: InputDecoration(
                hintText: _isListening
                    ? '말씀해주세요'
                    : _session.isSending
                    ? '응답을 기다리고 있어요'
                    : '챗봇에게 메시지 보내기',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: _sendMessage,
              onChanged: _session.updateDraft,
            ),
          ),
          IconButton(
            tooltip: _isListening ? '듣는 중' : '음성 입력',
            icon: Icon(
              _isListening ? Icons.mic : Icons.mic_none_rounded,
              color: micColor,
            ),
            onPressed: _session.isSending || _isListening
                ? null
                : _listenAndSend,
          ),
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: _session.isSending ? colors.primary : Colors.transparent,
              borderRadius: BorderRadius.circular(_session.isSending ? 10 : 20),
            ),
            child: IconButton(
              tooltip: _session.isSending ? '중지' : '전송',
              icon: Icon(
                _session.isSending ? Icons.stop_rounded : Icons.send_rounded,
                color: _session.isSending ? Colors.white : colors.primary,
              ),
              onPressed: _isListening
                  ? null
                  : _session.isSending
                  ? _session.stopStream
                  : () => _sendMessage(_controller.text),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBubble(FloatingChatMessage message, SeasonColors colors) {
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
        child: isUser
            ? Text(
                message.text,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  height: 1.35,
                ),
              )
            : _buildBotBubbleContent(message, colors),
      ),
    );
  }

  Widget _buildBotBubbleContent(
    FloatingChatMessage message,
    SeasonColors colors,
  ) {
    final hasProgress = message.progressSteps.isNotEmpty;
    final showProcessSummary =
        hasProgress ||
        (message.isStreaming && message.currentStatus.isNotEmpty);
    final hasText = message.text.trim().isNotEmpty;

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (showProcessSummary) _buildProcessSummary(message, colors),
        if (message.isProcessExpanded && hasProgress) ...[
          const SizedBox(height: 5),
          _buildProcessLog(message),
        ],
        if (hasText) ...[
          if (hasProgress) const SizedBox(height: 6),
          Text(
            message.text,
            style: const TextStyle(
              color: Color(0xFF2D2D2D),
              fontSize: 13,
              height: 1.35,
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildProcessSummary(
    FloatingChatMessage message,
    SeasonColors colors,
  ) {
    final summary = message.isStreaming
        ? message.currentStatus
        : '과정 ${message.progressSteps.length}단계 완료';
    final canExpand = message.progressSteps.isNotEmpty;
    final icon = message.isProcessExpanded
        ? Icons.keyboard_arrow_up_rounded
        : Icons.keyboard_arrow_down_rounded;

    return InkWell(
      onTap: canExpand ? () => _session.toggleProcessExpanded(message) : null,
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.only(bottom: 1),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (message.isStreaming) ...[
              SizedBox(
                width: 9,
                height: 9,
                child: CircularProgressIndicator(
                  strokeWidth: 1.4,
                  color: colors.primary.withValues(alpha: 0.75),
                ),
              ),
              const SizedBox(width: 5),
            ],
            Flexible(
              child: Text(
                summary,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: const Color(0xFF70747C),
                  fontSize: 10.5,
                  height: 1.25,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            if (canExpand) ...[
              const SizedBox(width: 2),
              Icon(icon, size: 14, color: const Color(0xFF8B9098)),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildProcessLog(FloatingChatMessage message) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: message.progressSteps
          .map(
            (step) => Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text(
                '• $step',
                style: const TextStyle(
                  color: Color(0xFF7A7F88),
                  fontSize: 10.5,
                  height: 1.25,
                ),
              ),
            ),
          )
          .toList(),
    );
  }
}
