import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import '../api_config.dart';

class CommentItem {
  final int id;
  final int userId;
  final String nickname;
  final String? profileImageUrl;
  final String content;
  final String createdAt;
  final bool mine;

  const CommentItem({
    required this.id,
    required this.userId,
    required this.nickname,
    this.profileImageUrl,
    required this.content,
    required this.createdAt,
    required this.mine,
  });

  factory CommentItem.fromJson(Map<String, dynamic> json) {
    return CommentItem(
      id: json['id'] as int,
      userId: json['userId'] as int? ?? 0,
      nickname: json['nickname'] as String? ?? '익명',
      profileImageUrl: json['profileImageUrl'] as String?,
      content: json['content'] as String,
      createdAt: json['createdAt'] as String? ?? '',
      mine: json['mine'] as bool? ?? false,
    );
  }
}

class CommentApiService {
  static String get _base => '${ApiConfig.backendBaseUrl()}/api/v1/community';

  static Future<List<CommentItem>> getComments(int postId, {String? accessToken}) async {
    try {
      final headers = <String, String>{};
      if (accessToken != null && accessToken.isNotEmpty) {
        headers['Authorization'] = 'Bearer $accessToken';
      }
      final res = await http.get(
        Uri.parse('$_base/posts/$postId/comments'),
        headers: headers,
      ).timeout(const Duration(seconds: 10));
      if (res.statusCode == 200) {
        final data = jsonDecode(res.body)['data'] as List;
        return data.map((e) => CommentItem.fromJson(e as Map<String, dynamic>)).toList();
      }
    } catch (e) {
      debugPrint('[Comment] 댓글 조회 실패: $e');
    }
    return [];
  }

  static Future<CommentItem?> addComment(String accessToken, int postId, String content) async {
    try {
      final res = await http.post(
        Uri.parse('$_base/posts/$postId/comments'),
        headers: {'Authorization': 'Bearer $accessToken', 'Content-Type': 'application/json'},
        body: jsonEncode({'content': content}),
      ).timeout(const Duration(seconds: 10));
      if (res.statusCode == 201) {
        final data = jsonDecode(res.body)['data'] as Map<String, dynamic>;
        return CommentItem.fromJson(data);
      }
    } catch (e) {
      debugPrint('[Comment] 댓글 작성 실패: $e');
    }
    return null;
  }

  static Future<bool> deleteComment(String accessToken, int postId, int commentId) async {
    try {
      final res = await http.delete(
        Uri.parse('$_base/posts/$postId/comments/$commentId'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));
      return res.statusCode == 200;
    } catch (e) {
      debugPrint('[Comment] 댓글 삭제 실패: $e');
      return false;
    }
  }
}
