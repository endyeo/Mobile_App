import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import '../api_config.dart';

class FlowerSpotApiService {
  static String get _base => ApiConfig.backendBaseUrl();

  static Future<Map<String, dynamic>> identifyPlant(File imageFile) async {
    try {
      final request = http.MultipartRequest(
        'POST', Uri.parse('$_base/api/v1/flower-spots/identify'),
      );
      request.files.add(await http.MultipartFile.fromPath('image', imageFile.path));

      final response = await request.send().timeout(const Duration(seconds: 30));
      final body = await http.Response.fromStream(response);

      if (response.statusCode == 200) {
        final data = jsonDecode(body.body);
        return data['data'] as Map<String, dynamic>;
      }
      return {'plantName': '기타', 'confidence': 0.0, 'isPlant': false};
    } catch (e) {
      debugPrint('[FlowerSpot] 식물 인식 실패: $e');
      return {'plantName': '기타', 'confidence': 0.0, 'isPlant': false};
    }
  }

  static Future<void> createFlowerSpot({
    required String accessToken,
    required File image,
    String? content,
    required String plantName,
    required double plantConfidence,
    double? latitude,
    double? longitude,
    String? address,
    bool notifyOthers = false,
  }) async {
    final request = http.MultipartRequest(
      'POST', Uri.parse('$_base/api/v1/flower-spots'),
    );
    request.headers['Authorization'] = 'Bearer $accessToken';
    request.files.add(await http.MultipartFile.fromPath('image', image.path));
    request.fields['plantName'] = plantName;
    request.fields['plantConfidence'] = plantConfidence.toString();
    request.fields['notifyOthers'] = notifyOthers.toString();
    if (content != null && content.isNotEmpty) request.fields['content'] = content;
    if (latitude != null) request.fields['latitude'] = latitude.toString();
    if (longitude != null) request.fields['longitude'] = longitude.toString();
    if (address != null) request.fields['address'] = address;

    final response = await request.send().timeout(const Duration(seconds: 30));
    if (response.statusCode != 201) {
      throw Exception('게시 실패: ${response.statusCode}');
    }
  }

  static Future<List<Map<String, dynamic>>> getFlowerSpots({
    double? lat, double? lng,
    double radius = 5000, int days = 7, int? cursor,
  }) async {
    try {
      final params = <String, String>{
        'radius': radius.toString(), 'days': days.toString(),
        if (lat != null) 'lat': lat.toString(),
        if (lng != null) 'lng': lng.toString(),
        if (cursor != null) 'cursor': cursor.toString(),
      };
      final uri = Uri.parse('$_base/api/v1/flower-spots').replace(queryParameters: params);
      final response = await http.get(uri).timeout(const Duration(seconds: 10));
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        final posts = data['data']['posts'] as List;
        return posts.cast<Map<String, dynamic>>();
      }
    } catch (e) {
      debugPrint('[FlowerSpot] 목록 조회 실패: $e');
    }
    return [];
  }
}
