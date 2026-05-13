import 'dart:convert';
import 'package:http/http.dart' as http;
import '../api_config.dart';

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
  static String get _base => ApiConfig.backendBaseUrl();

  static Future<List<FlowerBookItem>> getByMonth(int month) async {
    final res = await http.get(
      Uri.parse('$_base/api/v1/flowers/monthly/$month'),
    ).timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final body = jsonDecode(res.body);
      final data = body['data'] as List;
      return data.map((e) => FlowerBookItem.fromJson(e as Map<String, dynamic>)).toList();
    }
    throw Exception('꽃 목록을 불러오지 못했습니다.');
  }

  static Future<FlowerBookDetail> getDetail(int id) async {
    final res = await http.get(
      Uri.parse('$_base/api/v1/flowers/$id'),
    ).timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final body = jsonDecode(res.body);
      return FlowerBookDetail.fromJson(body['data'] as Map<String, dynamic>);
    }
    throw Exception('꽃 상세 정보를 불러오지 못했습니다.');
  }

  static Future<List<FlowerBookItem>> search(String keyword) async {
    final res = await http.get(
      Uri.parse('$_base/api/v1/flowers/search?keyword=${Uri.encodeComponent(keyword)}'),
    ).timeout(const Duration(seconds: 10));

    if (res.statusCode == 200) {
      final body = jsonDecode(res.body);
      final data = (body['data']['flowers']) as List;
      return data.map((e) => FlowerBookItem.fromJson(e as Map<String, dynamic>)).toList();
    }
    return [];
  }
}
