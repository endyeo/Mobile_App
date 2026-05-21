import 'package:flutter/foundation.dart';
import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import '../api_config.dart';

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
    final String? candidate = (flowerSpecies != null && flowerSpecies!.isNotEmpty)
        ? flowerSpecies
        : plantName;
    if (candidate == null || candidate.isEmpty || candidate == '기타') return null;
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

  const FeedResult({required this.posts, this.nextCursor, this.hasNext = false});
}

class CommunityApiService {
  static String get _baseUrl => '${ApiConfig.backendBaseUrl()}/api/v1/community';

  static Future<FeedResult> getPosts(String accessToken, {int? cursor}) async {
    try {
      final uri = Uri.parse('$_baseUrl/posts').replace(
        queryParameters: {
          if (cursor != null) 'cursor': cursor.toString(),
          'limit': '10',
        },
      );
      final response = await http.get(uri,
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(response.body);
        final data = body['data'] as Map<String, dynamic>;
        final posts = (data['posts'] as List)
            .map((e) => CommunityPost.fromJson(e as Map<String, dynamic>))
            .toList();
        return FeedResult(
          posts: posts,
          nextCursor: data['nextCursor'] as int?,
          hasNext: data['hasNext'] as bool? ?? false,
        );
      }
    } catch (e) { debugPrint('[API Error] $e'); }
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
      final request = http.MultipartRequest('POST', Uri.parse('$_baseUrl/posts'));
      request.headers['Authorization'] = 'Bearer $accessToken';
      request.fields['content'] = content;
      if (flowerSpecies != null) request.fields['flowerSpecies'] = flowerSpecies;
      if (latitude != null) request.fields['latitude'] = latitude.toString();
      if (longitude != null) request.fields['longitude'] = longitude.toString();
      if (address != null) request.fields['address'] = address;
      if (image != null) {
        request.files.add(await http.MultipartFile.fromPath('image', image.path));
      }

      final streamedResponse = await request.send().timeout(const Duration(seconds: 30));
      final response = await http.Response.fromStream(streamedResponse);

      if (response.statusCode == 201) {
        final body = jsonDecode(response.body);
        return CommunityPost.fromJson(body['data'] as Map<String, dynamic>);
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return null;
  }

  static Future<Map<String, dynamic>> toggleLike(String accessToken, int postId) async {
    try {
      final response = await http.post(
        Uri.parse('$_baseUrl/posts/$postId/like'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 5));

      if (response.statusCode == 200) {
        return jsonDecode(response.body)['data'] as Map<String, dynamic>;
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return {};
  }

  static Future<Map<String, dynamic>> toggleSave(String accessToken, int postId) async {
    try {
      final response = await http.post(
        Uri.parse('$_baseUrl/posts/$postId/save'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 5));

      if (response.statusCode == 200) {
        return jsonDecode(response.body)['data'] as Map<String, dynamic>;
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return {};
  }

}
