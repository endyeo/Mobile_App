import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../app_actions/app_action_runtime.dart';
import '../services/chatbot_service.dart';
import '../theme/season_theme.dart';

class ChatFloatingButton extends StatefulWidget {
  const ChatFloatingButton({super.key});

  @override
  State<ChatFloatingButton> createState() => _ChatFloatingButtonState();
}

class _ChatFloatingButtonState extends State<ChatFloatingButton> {
  static final List<_FloatingChatMessage> _messages = [];
  static final String _sessionId = const Uuid().v4();

  final TextEditingController _controller = TextEditingController();
  final ChatbotService _chatbotService = ChatbotService();
  bool _showComposer = false;
  bool _showHistory = false;
  bool _isSending = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _sendMessage(String rawText) async {
    final text = rawText.trim();
    if (text.isEmpty || _isSending) return;

    _controller.clear();
    FocusScope.of(context).unfocus();
    setState(() {
      _messages.add(_FloatingChatMessage.user(text));
      _isSending = true;
      _showHistory = true;
    });

    try {
      final response = await _chatbotService.sendMessage(
        message: text,
        sessionId: _sessionId,
        lat: 37.5665,
        lng: 126.9780,
      );
      if (!mounted) return;
      setState(() {
        _messages.add(_FloatingChatMessage.bot(response.reply));
        _isSending = false;
      });
      await AppActionRuntime.execute(context, response.actions);
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _messages.add(_FloatingChatMessage.bot('응답을 가져오지 못했습니다.'));
        _isSending = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    final size = MediaQuery.sizeOf(context);
    final dockWidth = (size.width - 24).clamp(296.0, 420.0);

    if (!_showComposer) {
      return FloatingActionButton(
        heroTag: 'global-chat-floating-button',
        tooltip: '챗봇',
        backgroundColor: colors.primary,
        foregroundColor: Colors.white,
        elevation: 8,
        shape: const CircleBorder(),
        onPressed: () {
          setState(() {
            _showComposer = true;
            _showHistory = _messages.isNotEmpty;
          });
        },
        child: const Icon(Icons.chat_bubble_outline, size: 26),
      );
    }

    return SizedBox(
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
          BoxShadow(color: Colors.black.withValues(alpha: 0.12), blurRadius: 18, offset: const Offset(0, 8)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Icon(Icons.chat_bubble_outline, color: colors.primary, size: 18),
              const SizedBox(width: 8),
              const Expanded(child: Text('챗봇 대화', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 13))),
              IconButton(
                visualDensity: VisualDensity.compact,
                icon: const Icon(Icons.close, size: 18),
                onPressed: () => setState(() { _showHistory = false; _showComposer = false; }),
              ),
            ],
          ),
          const SizedBox(height: 4),
          if (_messages.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 18),
              child: Text('아직 대화 내역이 없습니다.', style: TextStyle(color: Colors.grey[600], fontSize: 12)),
            )
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

  Widget _buildInputDock(SeasonColors colors) {
    return Container(
      height: 52,
      padding: const EdgeInsets.only(left: 6, right: 4),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.94),
        borderRadius: BorderRadius.circular(26),
        border: Border.all(color: colors.primary.withValues(alpha: 0.16)),
        boxShadow: [BoxShadow(color: colors.primary.withValues(alpha: 0.18), blurRadius: 16, offset: const Offset(0, 6))],
      ),
      child: Row(
        children: [
          IconButton(
            tooltip: '챗봇 대화 내역',
            icon: Icon(_showHistory ? Icons.expand_more : Icons.chat_bubble_outline, color: colors.primary),
            onPressed: () => setState(() => _showHistory = !_showHistory),
          ),
          Expanded(
            child: TextField(
              controller: _controller,
              enabled: !_isSending,
              textInputAction: TextInputAction.send,
              decoration: InputDecoration(
                hintText: _isSending ? '챗봇이 답변 중입니다' : '챗봇에게 메시지 보내기',
                border: InputBorder.none,
                isDense: true,
              ),
              onSubmitted: _sendMessage,
            ),
          ),
          IconButton(
            tooltip: '전송',
            icon: Icon(Icons.send_rounded, color: colors.primary),
            onPressed: _isSending ? null : () => _sendMessage(_controller.text),
          ),
          IconButton(
            tooltip: '닫기',
            icon: Icon(Icons.close, color: Colors.grey[500]),
            onPressed: () => setState(() { _showHistory = false; _showComposer = false; }),
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
          style: TextStyle(color: isUser ? Colors.white : const Color(0xFF2D2D2D), fontSize: 13, height: 1.35),
        ),
      ),
    );
  }
}

class _FloatingChatMessage {
  const _FloatingChatMessage._({required this.text, required this.isUser});
  factory _FloatingChatMessage.user(String text) => _FloatingChatMessage._(text: text, isUser: true);
  factory _FloatingChatMessage.bot(String text) => _FloatingChatMessage._(text: text, isUser: false);
  final String text;
  final bool isUser;
}
