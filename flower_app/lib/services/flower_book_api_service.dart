import 'package:dio/dio.dart';

import 'api_client.dart';

class FlowerBookItem {
  final int id;
  final String name;
  final String? categoryName;
  final String? categoryEmoji;
  final int? bloomMonth;
  final int? bloomDay;
  final String? flowerLanguage;
  final String? imageUrl;

  const FlowerBookItem({
    required this.id,
    required this.name,
    this.categoryName,
    this.categoryEmoji,
    this.bloomMonth,
    this.bloomDay,
    this.flowerLanguage,
    this.imageUrl,
  });

  factory FlowerBookItem.fromJson(Map<String, dynamic> json) {
    return FlowerBookItem(
      id: json['id'] as int,
      name: json['name'] as String,
      categoryName: json['categoryName'] as String?,
      categoryEmoji: json['categoryEmoji'] as String?,
      bloomMonth: json['bloomMonth'] as int?,
      bloomDay: json['bloomDay'] as int?,
      flowerLanguage: json['flowerLanguage'] as String?,
      imageUrl: json['imageUrl'] as String?,
    );
  }

  String get dateString {
    if (bloomMonth == null) return '';
    if (bloomDay == null) return '${bloomMonth}월';
    return '${bloomMonth}월 ${bloomDay}일';
  }
}

class FlowerBookDetail {
  final int id;
  final String name;
  final String? scientificName;
  final String? categoryName;
  final String? categoryEmoji;
  final int? bloomMonth;
  final int? bloomDay;
  final String? flowerLanguage;
  final String? description;
  final String? growTips;
  final String? imageUrl;

  const FlowerBookDetail({
    required this.id,
    required this.name,
    this.scientificName,
    this.categoryName,
    this.categoryEmoji,
    this.bloomMonth,
    this.bloomDay,
    this.flowerLanguage,
    this.description,
    this.growTips,
    this.imageUrl,
  });

  factory FlowerBookDetail.fromJson(Map<String, dynamic> json) {
    return FlowerBookDetail(
      id: json['id'] as int,
      name: json['name'] as String,
      scientificName: json['scientificName'] as String?,
      categoryName: json['categoryName'] as String?,
      categoryEmoji: json['categoryEmoji'] as String?,
      bloomMonth: json['bloomMonth'] as int?,
      bloomDay: json['bloomDay'] as int?,
      flowerLanguage: json['flowerLanguage'] as String?,
      description: json['description'] as String?,
      growTips: json['growTips'] as String?,
      imageUrl: json['imageUrl'] as String?,
    );
  }
}

class FlowerBookApiService {
  static Future<List<FlowerBookItem>> getByMonth(int month) async {
    final Response<dynamic> response = await ApiClient.dio.get(
      '/api/v1/flowers/monthly/$month',
    );
    if (response.statusCode == 200 && response.data is Map) {
      final List data = (response.data as Map)['data'] as List;
      return data
          .map((e) => FlowerBookItem.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    throw Exception('꽃 목록을 불러오지 못했습니다.');
  }

  static Future<FlowerBookDetail> getDetail(int id) async {
    final Response<dynamic> response = await ApiClient.dio.get(
      '/api/v1/flowers/$id',
    );
    if (response.statusCode == 200 && response.data is Map) {
      return FlowerBookDetail.fromJson(
        (response.data as Map)['data'] as Map<String, dynamic>,
      );
    }
    throw Exception('꽃 상세 정보를 불러오지 못했습니다.');
  }

  static Future<List<FlowerBookItem>> search(String keyword) async {
    final Response<dynamic> response = await ApiClient.dio.get(
      '/api/v1/flowers/search',
      queryParameters: <String, dynamic>{'keyword': keyword},
    );
    if (response.statusCode == 200 && response.data is Map) {
      final Map data = (response.data as Map)['data'] as Map;
      final List flowers = data['flowers'] as List;
      return flowers
          .map((e) => FlowerBookItem.fromJson(e as Map<String, dynamic>))
          .toList();
    }
    return <FlowerBookItem>[];
  }
}
