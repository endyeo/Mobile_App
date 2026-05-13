import 'package:flutter/foundation.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../api_config.dart';

class SavedPostItem {
  final int id;
  final String user;
  final String avatar;
  final String content;
  final String flowerSpecies;
  final int likeCount;
  final String time;

  const SavedPostItem({
    required this.id, required this.user, required this.avatar,
    required this.content, required this.flowerSpecies,
    required this.likeCount, required this.time,
  });

  factory SavedPostItem.fromJson(Map<String, dynamic> json) {
    return SavedPostItem(
      id: json['id'] as int,
      user: json['nickname'] as String? ?? '익명',
      avatar: '🌸',
      content: json['content'] as String,
      flowerSpecies: json['flowerSpecies'] as String? ?? '',
      likeCount: json['likeCount'] as int? ?? 0,
      time: json['createdAt'] as String? ?? '',
    );
  }
}

class SavedSpotItem {
  final int id;
  final String name;
  final String flowerSpecies;
  final String emoji;
  final String location;
  final String bloomPeriod;

  const SavedSpotItem({
    required this.id, required this.name, required this.flowerSpecies,
    required this.emoji, required this.location, required this.bloomPeriod,
  });

  factory SavedSpotItem.fromJson(Map<String, dynamic> json) {
    return SavedSpotItem(
      id: json['id'] as int,
      name: json['name'] as String,
      flowerSpecies: json['flowerSpecies'] as String? ?? '',
      emoji: '🌸',
      location: json['address'] as String? ?? '',
      bloomPeriod: json['bloomPeriod'] as String? ?? '',
    );
  }
}

class SavedApiService {
  static Future<List<SavedPostItem>> getSavedPosts(String accessToken) async {
    try {
      final response = await http.get(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/saved/posts'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(response.body);
        final data = body['data'] as List;
        return data.map((e) => SavedPostItem.fromJson(e as Map<String, dynamic>)).toList();
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return [];
  }

  static Future<List<SavedSpotItem>> getSavedSpots(String accessToken) async {
    try {
      final response = await http.get(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/saved/spots'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(response.body);
        final data = body['data'] as List;
        return data.map((e) => SavedSpotItem.fromJson(e as Map<String, dynamic>)).toList();
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return [];
  }

  static Future<void> unsavePost(String accessToken, int postId) async {
    try {
      await http.delete(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/saved/posts/$postId'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 5));
    } catch (e) { debugPrint('[API Error] $e'); }
  }

  static Future<void> unsaveSpot(String accessToken, int spotId) async {
    try {
      await http.delete(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/saved/spots/$spotId'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 5));
    } catch (e) { debugPrint('[API Error] $e'); }
  }

}
