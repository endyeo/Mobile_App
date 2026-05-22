import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'dart:io';

import 'api_client.dart';

class CommunityPost {
  final int id;
  final int userId;
  final String user;
  final String? profileImageUrl;
  final String content;
  final String? flowerSpecies;
  final String? plantName;
  final String? imageUrl;
  final String? address;
  final double? latitude;
  final double? longitude;
  int likeCount;
  int commentCount;
  bool liked;
  bool saved;
  final String time;

  CommunityPost({
    required this.id,
    required this.userId,
    required this.user,
    this.profileImageUrl,
    required this.content,
    this.flowerSpecies,
    this.plantName,
    this.imageUrl,
    this.address,
    this.latitude,
    this.longitude,
    required this.likeCount,
    this.commentCount = 0,
    this.liked = false,
    this.saved = false,
    required this.time,
  });

  /// 꽃 종류 표시용 — flowerSpecies(일반 게시글) 또는 plantName(꽃 명소) 둘 중 하나.
  /// "기타"는 인식 실패 fallback이라 표시 안 함.
  String? get displaySpecies {
    final String? candidate =
        (flowerSpecies != null && flowerSpecies!.isNotEmpty)
        ? flowerSpecies
        : plantName;
    if (candidate == null || candidate.isEmpty || candidate == '기타')
      return null;
    return candidate;
  }

  factory CommunityPost.fromJson(Map<String, dynamic> json) {
    return CommunityPost(
      id: (json['id'] as num).toInt(),
      userId: json['userId'] as int? ?? 0,
      user: json['nickname'] as String? ?? '익명',
      profileImageUrl: json['profileImageUrl'] as String?,
      content: json['content'] as String? ?? '',
      flowerSpecies: json['flowerSpecies'] as String?,
      plantName: json['plantName'] as String?,
      imageUrl: json['imageUrl'] as String?,
      address: json['address'] as String?,
      latitude: (json['latitude'] as num?)?.toDouble(),
      longitude: (json['longitude'] as num?)?.toDouble(),
      likeCount: json['likeCount'] as int? ?? 0,
      commentCount: json['commentCount'] as int? ?? 0,
      liked: json['liked'] as bool? ?? false,
      saved: json['saved'] as bool? ?? false,
      time: json['createdAt'] as String? ?? '',
    );
  }
}

class FeedResult {
  final List<CommunityPost> posts;
  final int? nextCursor;
  final bool hasNext;

  const FeedResult({
    required this.posts,
    this.nextCursor,
    this.hasNext = false,
  });
}

class CommunityApiService {
  static const String _basePath = '/api/v1/community';

  /// accessToken 매개변수는 호환성을 위해 남겨두지만 사용하지 않음.
  /// 인증 헤더는 ApiClient의 AuthInterceptor가 자동으로 첨부.
  static Future<FeedResult> getPosts(String accessToken, {int? cursor}) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.get(
        '$_basePath/posts',
        queryParameters: <String, dynamic>{
          if (cursor != null) 'cursor': cursor,
          'limit': 10,
        },
      );
      if (response.statusCode == 200 && response.data is Map) {
        final Map data = (response.data as Map)['data'] as Map;
        final List posts = data['posts'] as List;
        return FeedResult(
          posts: posts
              .map((e) => CommunityPost.fromJson(e as Map<String, dynamic>))
              .toList(),
          nextCursor: data['nextCursor'] as int?,
          hasNext: data['hasNext'] as bool? ?? false,
        );
      }
    } catch (e) {
      debugPrint('[API Error] $e');
    }
    return const FeedResult(posts: []);
  }

  static Future<CommunityPost?> createPost({
    required String accessToken,
    required String content,
    String? flowerSpecies,
    File? image,
    double? latitude,
    double? longitude,
    String? address,
  }) async {
    try {
      final FormData form = FormData.fromMap(<String, dynamic>{
        'content': content,
        if (flowerSpecies != null) 'flowerSpecies': flowerSpecies,
        if (latitude != null) 'latitude': latitude.toString(),
        if (longitude != null) 'longitude': longitude.toString(),
        if (address != null) 'address': address,
        if (image != null) 'image': await MultipartFile.fromFile(image.path),
      });
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/posts',
        data: form,
      );
      if (response.statusCode == 201 && response.data is Map) {
        return CommunityPost.fromJson(
          (response.data as Map)['data'] as Map<String, dynamic>,
        );
      }
    } catch (e) {
      debugPrint('[API Error] $e');
    }
    return null;
  }

  static Future<Map<String, dynamic>> toggleLike(
    String accessToken,
    int postId,
  ) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/posts/$postId/like',
      );
      if (response.statusCode == 200 && response.data is Map) {
        return ((response.data as Map)['data'] as Map).cast<String, dynamic>();
      }
    } catch (e) {
      debugPrint('[API Error] $e');
    }
    return <String, dynamic>{};
  }

  /// 내가 좋아요 한 게시글 목록 (최근 좋아요 순)
  static Future<FeedResult> getLikedPosts({
    int page = 0,
    int limit = 20,
  }) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.get(
        '$_basePath/posts/liked',
        queryParameters: <String, dynamic>{'page': page, 'limit': limit},
      );
      if (response.statusCode == 200 && response.data is Map) {
        final Map data = (response.data as Map)['data'] as Map;
        final List posts = data['posts'] as List;
        return FeedResult(
          posts: posts
              .map((e) => CommunityPost.fromJson(e as Map<String, dynamic>))
              .toList(),
          nextCursor: data['nextCursor'] as int?,
          hasNext: data['hasNext'] as bool? ?? false,
        );
      }
    } catch (e) {
      debugPrint('[API Error] $e');
    }
    return const FeedResult(posts: []);
  }

  static Future<Map<String, dynamic>> toggleSave(
    String accessToken,
    int postId,
  ) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/posts/$postId/save',
      );
      if (response.statusCode == 200 && response.data is Map) {
        return ((response.data as Map)['data'] as Map).cast<String, dynamic>();
      }
    } catch (e) {
      debugPrint('[API Error] $e');
    }
    return <String, dynamic>{};
  }
}
