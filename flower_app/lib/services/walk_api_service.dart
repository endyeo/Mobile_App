import 'package:flutter/foundation.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../api_config.dart';

class WalkRecord {
  final String day;
  final String recordDate; // ISO "yyyy-MM-dd"
  final int stepCount;

  const WalkRecord({
    required this.day,
    required this.recordDate,
    required this.stepCount,
  });

  factory WalkRecord.fromJson(Map<String, dynamic> json) {
    final String raw = json['recordDate'] as String? ?? '';
    final String iso = raw.length >= 10 ? raw.substring(0, 10) : raw;
    return WalkRecord(
      day: _toDisplayDay(iso),
      recordDate: iso,
      stepCount: json['stepCount'] as int? ?? 0,
    );
  }

  static String _toDisplayDay(String iso) {
    if (iso.length < 10) return iso;
    final parts = iso.substring(0, 10).split('-');
    if (parts.length != 3) return iso;
    return '${int.tryParse(parts[1]) ?? parts[1]}/${int.tryParse(parts[2]) ?? parts[2]}';
  }
}

class WalkApiService {
  static Future<List<WalkRecord>> getWeeklyRecords(String accessToken) async {
    if (accessToken.isEmpty) return [];
    try {
      final response = await http
          .get(
            Uri.parse(
              '${ApiConfig.backendBaseUrl()}/api/v1/walk/records/weekly',
            ),
            headers: {'Authorization': 'Bearer $accessToken'},
          )
          .timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(utf8.decode(response.bodyBytes));
        final data = body['data'] as List;
        return data
            .map((e) => WalkRecord.fromJson(e as Map<String, dynamic>))
            .toList();
      }
    } catch (e) {
      debugPrint('[Walk API] getWeeklyRecords 실패: $e');
    }
    return [];
  }

  static Future<bool> syncSteps(String accessToken, int steps) async {
    if (accessToken.isEmpty) {
      debugPrint('[Walk API] syncSteps: 토큰 비어있음');
      return false;
    }
    try {
      final response = await http
          .post(
            Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/walk/sync'),
            headers: {
              'Authorization': 'Bearer $accessToken',
              'Content-Type': 'application/json',
            },
            body: jsonEncode({'stepCount': steps}),
          )
          .timeout(const Duration(seconds: 10));
      debugPrint(
        '[Walk API] syncSteps HTTP ${response.statusCode} body=${response.body}',
      );
      return response.statusCode == 200;
    } catch (e) {
      debugPrint('[Walk API] syncSteps 예외: $e');
      return false;
    }
  }
}
