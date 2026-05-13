import 'package:flutter/material.dart';
import '../models/chat_message.dart';
import '../theme/season_theme.dart';

class MessageBubble extends StatelessWidget {
  final ChatMessage message;

  const MessageBubble({super.key, required this.message});

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    if (message.role == MessageRole.loading) {
      return _buildLoadingBubble(colors);
    }

    final isUser = message.role == MessageRole.user;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isUser) _buildAvatar(colors),
          const SizedBox(width: 8),
          Flexible(
            child: Column(
              crossAxisAlignment: isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [_buildBubble(isUser, colors)],
            ),
          ),
          const SizedBox(width: 8),
          if (isUser) _buildUserAvatar(colors),
        ],
      ),
    );
  }

  Widget _buildAvatar(SeasonColors colors) {
    return CircleAvatar(
      radius: 18,
      backgroundColor: colors.primary.withAlpha(38),
      child: Icon(Icons.smart_toy_outlined, color: colors.primary, size: 18),
    );
  }

  Widget _buildUserAvatar(SeasonColors colors) {
    return CircleAvatar(
      radius: 18,
      backgroundColor: colors.primary,
      child: const Icon(Icons.person, color: Colors.white, size: 18),
    );
  }

  Widget _buildBubble(bool isUser, SeasonColors colors) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: isUser ? colors.primary : Colors.white.withAlpha(242),
        borderRadius: BorderRadius.only(
          topLeft: const Radius.circular(18),
          topRight: const Radius.circular(18),
          bottomLeft: Radius.circular(isUser ? 18 : 4),
          bottomRight: Radius.circular(isUser ? 4 : 18),
        ),
        boxShadow: [BoxShadow(color: Colors.black.withAlpha(20), blurRadius: 6, offset: const Offset(0, 2))],
      ),
      child: Text(
        message.content,
        style: TextStyle(
          color: isUser ? Colors.white : const Color(0xFF2D2D2D),
          fontSize: 15,
          height: 1.4,
        ),
      ),
    );
  }

  Widget _buildLoadingBubble(SeasonColors colors) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          CircleAvatar(
            radius: 18,
            backgroundColor: colors.primary.withAlpha(38),
            child: Icon(Icons.smart_toy_outlined, color: colors.primary, size: 18),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: const BorderRadius.only(
                topLeft: Radius.circular(18),
                topRight: Radius.circular(18),
                bottomRight: Radius.circular(18),
                bottomLeft: Radius.circular(4),
              ),
              boxShadow: [BoxShadow(color: Colors.black.withAlpha(20), blurRadius: 6, offset: const Offset(0, 2))],
            ),
            child: _TypingIndicator(color: colors.primary),
          ),
        ],
      ),
    );
  }
}

class _TypingIndicator extends StatefulWidget {
  const _TypingIndicator({required this.color});
  final Color color;

  @override
  State<_TypingIndicator> createState() => _TypingIndicatorState();
}

class _TypingIndicatorState extends State<_TypingIndicator> with TickerProviderStateMixin {
  late final List<AnimationController> _controllers;
  late final List<Animation<double>> _animations;

  @override
  void initState() {
    super.initState();
    _controllers = List.generate(3, (i) {
      return AnimationController(vsync: this, duration: const Duration(milliseconds: 400))
        ..repeat(reverse: true, period: Duration(milliseconds: 400 + i * 150));
    });
    _animations = _controllers
        .map((c) => Tween<double>(begin: 0, end: -6).animate(CurvedAnimation(parent: c, curve: Curves.easeInOut)))
        .toList();
    for (int i = 0; i < _controllers.length; i++) {
      Future.delayed(Duration(milliseconds: i * 150), () {
        if (mounted) _controllers[i].repeat(reverse: true);
      });
    }
  }

  @override
  void dispose() {
    for (final c in _controllers) {
      c.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (i) {
        return AnimatedBuilder(
          animation: _animations[i],
          builder: (_, __) => Transform.translate(
            offset: Offset(0, _animations[i].value),
            child: Container(
              margin: const EdgeInsets.symmetric(horizontal: 3),
              width: 8,
              height: 8,
              decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle),
            ),
          ),
        );
      }),
    );
  }
}
