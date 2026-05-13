import 'chat_action.dart';

enum MessageRole { user, bot, loading }

class ChatMessage {
  final MessageRole role;
  final String content;
  final ChatAction? action;

  const ChatMessage({
    required this.role,
    required this.content,
    this.action,
  });

  factory ChatMessage.user(String text) =>
      ChatMessage(role: MessageRole.user, content: text);

  factory ChatMessage.loading() =>
      const ChatMessage(role: MessageRole.loading, content: '');

  factory ChatMessage.bot(String text, {ChatAction? action}) =>
      ChatMessage(role: MessageRole.bot, content: text, action: action);
}
