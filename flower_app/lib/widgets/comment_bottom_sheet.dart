import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../services/comment_api_service.dart';
import '../theme/season_theme.dart';

class CommentBottomSheet extends StatefulWidget {
  final int postId;
  final VoidCallback? onCommentAdded;

  const CommentBottomSheet({
    super.key,
    required this.postId,
    this.onCommentAdded,
  });

  @override
  State<CommentBottomSheet> createState() => _CommentBottomSheetState();
}

class _CommentBottomSheetState extends State<CommentBottomSheet> {
  final TextEditingController _controller = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  List<CommentItem> _comments = [];
  bool _isLoading = true;
  bool _isSending = false;
  String _accessToken = '';

  @override
  void initState() {
    super.initState();
    _init();
  }

  @override
  void dispose() {
    _controller.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    _accessToken = prefs.getString('accessToken') ?? '';
    await _loadComments();
  }

  Future<void> _loadComments() async {
    final comments = await CommentApiService.getComments(
      widget.postId, accessToken: _accessToken,
    );
    if (mounted) setState(() { _comments = comments; _isLoading = false; });
  }

  Future<void> _send() async {
    final text = _controller.text.trim();
    if (text.isEmpty || _isSending) return;
    _controller.clear();
    FocusScope.of(context).unfocus();
    setState(() => _isSending = true);

    final comment = await CommentApiService.addComment(_accessToken, widget.postId, text);
    if (mounted) {
      setState(() {
        _isSending = false;
        if (comment != null) _comments.add(comment);
      });
      widget.onCommentAdded?.call();
      // 새 댓글로 스크롤
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scrollController.hasClients) {
          _scrollController.animateTo(
            _scrollController.position.maxScrollExtent,
            duration: const Duration(milliseconds: 300),
            curve: Curves.easeOut,
          );
        }
      });
    }
  }

  Future<void> _deleteComment(CommentItem comment) async {
    final ok = await CommentApiService.deleteComment(
      _accessToken, widget.postId, comment.id,
    );
    if (ok && mounted) {
      setState(() => _comments.remove(comment));
      widget.onCommentAdded?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();
    return DraggableScrollableSheet(
      initialChildSize: 0.6,
      minChildSize: 0.3,
      maxChildSize: 0.9,
      expand: false,
      builder: (context, scrollController) {
        return Container(
          decoration: const BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
          ),
          child: Column(
            children: [
              // 드래그 핸들 + 헤더
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 8, 8),
                child: Row(
                  children: [
                    Center(
                      child: Container(
                        width: 40, height: 4,
                        decoration: BoxDecoration(
                          color: Colors.grey[300],
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      '댓글 ${_comments.length}개',
                      style: TextStyle(fontWeight: FontWeight.bold, color: colors.primary),
                    ),
                    const Spacer(),
                    IconButton(
                      icon: const Icon(Icons.close, size: 20),
                      onPressed: () => Navigator.pop(context),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              // 댓글 목록
              Expanded(
                child: _isLoading
                    ? Center(child: CircularProgressIndicator(color: colors.primary))
                    : _comments.isEmpty
                        ? Center(
                            child: Text('첫 댓글을 남겨보세요 🌸',
                              style: TextStyle(color: Colors.grey[400], fontSize: 14)),
                          )
                        : ListView.builder(
                            controller: scrollController,
                            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                            itemCount: _comments.length,
                            itemBuilder: (context, i) => _buildCommentItem(_comments[i], colors),
                          ),
              ),
              // 입력창
              Container(
                padding: EdgeInsets.only(
                  left: 12, right: 8, top: 8,
                  bottom: MediaQuery.of(context).viewInsets.bottom +
                      MediaQuery.of(context).padding.bottom + 8,
                ),
                decoration: BoxDecoration(
                  color: Colors.white,
                  border: Border(top: BorderSide(color: Colors.grey.shade200)),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _controller,
                        maxLines: 3,
                        minLines: 1,
                        maxLength: 500,
                        decoration: InputDecoration(
                          hintText: '댓글을 입력하세요',
                          hintStyle: TextStyle(color: Colors.grey[400], fontSize: 14),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(20),
                            borderSide: BorderSide(color: Colors.grey.shade300),
                          ),
                          enabledBorder: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(20),
                            borderSide: BorderSide(color: Colors.grey.shade300),
                          ),
                          contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                          counterText: '',
                        ),
                        onSubmitted: (_) => _send(),
                      ),
                    ),
                    const SizedBox(width: 8),
                    _isSending
                        ? SizedBox(width: 36, height: 36,
                            child: CircularProgressIndicator(color: colors.primary, strokeWidth: 2))
                        : IconButton(
                            icon: Icon(Icons.send_rounded, color: colors.primary),
                            onPressed: _send,
                          ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildCommentItem(CommentItem comment, SeasonColors colors) {
    return GestureDetector(
      onLongPress: comment.mine ? () => _showDeleteDialog(comment) : null,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            CircleAvatar(
              radius: 16,
              backgroundColor: colors.primary.withAlpha(30),
              backgroundImage: comment.profileImageUrl != null
                  ? NetworkImage(comment.profileImageUrl!) : null,
              child: comment.profileImageUrl == null
                  ? Text(comment.nickname.isNotEmpty ? comment.nickname[0] : '?',
                      style: TextStyle(color: colors.primary, fontSize: 12, fontWeight: FontWeight.bold))
                  : null,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text(comment.nickname,
                        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
                      const SizedBox(width: 6),
                      Text(_formatTime(comment.createdAt),
                        style: TextStyle(fontSize: 11, color: Colors.grey[400])),
                      if (comment.mine) ...[
                        const Spacer(),
                        GestureDetector(
                          onTap: () => _showDeleteDialog(comment),
                          child: Icon(Icons.close, size: 16, color: Colors.grey[400]),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(comment.content, style: const TextStyle(fontSize: 14, height: 1.4)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _showDeleteDialog(CommentItem comment) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('댓글 삭제'),
        content: const Text('이 댓글을 삭제할까요?'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('취소')),
          TextButton(
            onPressed: () { Navigator.pop(ctx); _deleteComment(comment); },
            child: const Text('삭제', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }

  String _formatTime(String createdAt) {
    if (createdAt.isEmpty) return '방금';
    try {
      final dt = DateTime.parse(createdAt);
      final diff = DateTime.now().difference(dt);
      if (diff.inMinutes < 1) return '방금';
      if (diff.inHours < 1) return '${diff.inMinutes}분 전';
      if (diff.inDays < 1) return '${diff.inHours}시간 전';
      if (diff.inDays < 7) return '${diff.inDays}일 전';
      return '${dt.month}/${dt.day}';
    } catch (_) { return ''; }
  }
}

// 댓글 버튼을 피드 카드에서 사용하는 헬퍼 함수
void showCommentSheet(BuildContext context, int postId, {VoidCallback? onCommentAdded}) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useRootNavigator: true, // 탭바 위로 시트가 뜨게
    backgroundColor: Colors.transparent,
    builder: (_) => CommentBottomSheet(postId: postId, onCommentAdded: onCommentAdded),
  );
}

// 댓글 수 표시 헬퍼 (999+ 처리)
String formatCommentCount(int count) {
  if (count > 999) return '999+';
  return '$count';
}
