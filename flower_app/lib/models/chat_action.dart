class ChatAction {
  final String type;
  final String? target;
  final Map<String, dynamic>? params;

  const ChatAction({
    required this.type,
    this.target,
    this.params,
  });

  factory ChatAction.fromJson(Map<String, dynamic> json) {
    return ChatAction(
      type: json['type'] as String? ?? '',
      target: json['target'] as String?,
      params: json['params'] as Map<String, dynamic>?,
    );
  }
}
