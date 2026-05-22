import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'api_client.dart';

class FlowerSpotApiService {
  static Future<Map<String, dynamic>> identifyPlant(File imageFile) async {
    try {
      final FormData form = FormData.fromMap(<String, dynamic>{
        'image': await MultipartFile.fromFile(imageFile.path),
      });
      final Response<dynamic> response = await ApiClient.dio.post(
        '/api/v1/flower-spots/identify',
        data: form,
        options: Options(sendTimeout: const Duration(seconds: 30)),
      );
      if (response.statusCode == 200 && response.data is Map) {
        return ((response.data as Map)['data'] as Map).cast<String, dynamic>();
      }
    } catch (e) {
      debugPrint('[FlowerSpot] 식물 인식 실패: $e');
    }
    return <String, dynamic>{
      'plantName': '기타',
      'confidence': 0.0,
      'isPlant': false,
    };
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
    final FormData form = FormData.fromMap(<String, dynamic>{
      'image': await MultipartFile.fromFile(image.path),
      'plantName': plantName,
      'plantConfidence': plantConfidence.toString(),
      'notifyOthers': notifyOthers.toString(),
      if (content != null && content.isNotEmpty) 'content': content,
      if (latitude != null) 'latitude': latitude.toString(),
      if (longitude != null) 'longitude': longitude.toString(),
      if (address != null) 'address': address,
    });
    final Response<dynamic> response = await ApiClient.dio.post(
      '/api/v1/flower-spots',
      data: form,
      options: Options(sendTimeout: const Duration(seconds: 30)),
    );
    if (response.statusCode != 201) {
      throw Exception('게시 실패: ${response.statusCode}');
    }
  }

  static Future<List<Map<String, dynamic>>> getFlowerSpots({
    double? lat,
    double? lng,
    double radius = 5000,
    int days = 7,
    int? cursor,
  }) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.get(
        '/api/v1/flower-spots',
        queryParameters: <String, dynamic>{
          'radius': radius,
          'days': days,
          if (lat != null) 'lat': lat,
          if (lng != null) 'lng': lng,
          if (cursor != null) 'cursor': cursor,
        },
      );
      if (response.statusCode == 200 && response.data is Map) {
        final Map data = (response.data as Map)['data'] as Map;
        final List posts = data['posts'] as List;
        return posts.cast<Map<String, dynamic>>();
      }
    } catch (e) {
      debugPrint('[FlowerSpot] 목록 조회 실패: $e');
    }
    return <Map<String, dynamic>>[];
  }
}
