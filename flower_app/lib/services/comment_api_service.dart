import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'api_client.dart';

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
  static const String _basePath = '/api/v1/community';

  /// accessToken은 호환성을 위해 남김. ApiClient AuthInterceptor가 자동 첨부.
  static Future<List<CommentItem>> getComments(
    int postId, {
    String? accessToken,
  }) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.get(
        '$_basePath/posts/$postId/comments',
      );
      if (response.statusCode == 200 && response.data is Map) {
        final List data = (response.data as Map)['data'] as List;
        return data
            .map((e) => CommentItem.fromJson(e as Map<String, dynamic>))
            .toList();
      }
    } catch (e) {
      debugPrint('[Comment] 댓글 조회 실패: $e');
    }
    return <CommentItem>[];
  }

  static Future<CommentItem?> addComment(
    String accessToken,
    int postId,
    String content,
  ) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/posts/$postId/comments',
        data: <String, dynamic>{'content': content},
      );
      if (response.statusCode == 201 && response.data is Map) {
        return CommentItem.fromJson(
          (response.data as Map)['data'] as Map<String, dynamic>,
        );
      }
    } catch (e) {
      debugPrint('[Comment] 댓글 작성 실패: $e');
    }
    return null;
  }

  static Future<bool> deleteComment(
    String accessToken,
    int postId,
    int commentId,
  ) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.delete(
        '$_basePath/posts/$postId/comments/$commentId',
      );
      return response.statusCode == 200;
    } catch (e) {
      debugPrint('[Comment] 댓글 삭제 실패: $e');
      return false;
    }
  }
}
