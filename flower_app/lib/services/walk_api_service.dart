import 'package:flutter/foundation.dart';
import 'dart:convert';
import 'package:http/http.dart' as http;
import '../api_config.dart';

class WalkRecord {
  final String day;
  final int stepCount;

  const WalkRecord({required this.day, required this.stepCount});

  factory WalkRecord.fromJson(Map<String, dynamic> json) {
    return WalkRecord(
      day: json['recordDate'] as String? ?? '',
      stepCount: json['stepCount'] as int? ?? 0,
    );
  }
}

class PointHistory {
  final String desc;
  final int amount;
  final String type;
  final String time;

  const PointHistory({required this.desc, required this.amount, required this.type, required this.time});
}

class WalkApiService {
  static Future<List<WalkRecord>> getWeeklyRecords(String accessToken) async {
    try {
      final response = await http.get(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/walk/records/weekly'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(response.body);
        final data = body['data'] as List;
        return data.map((e) => WalkRecord.fromJson(e as Map<String, dynamic>)).toList();
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return [];
  }

  static Future<int> getPointBalance(String accessToken) async {
    try {
      final response = await http.get(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/walk/points'),
        headers: {'Authorization': 'Bearer $accessToken'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final body = jsonDecode(response.body);
        return body['data']['balance'] as int? ?? 0;
      }
    } catch (e) { debugPrint('[API Error] $e'); }
    return 0;
  }

  static Future<void> syncSteps(String accessToken, int steps) async {
    try {
      await http.post(
        Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/walk/sync'),
        headers: {'Authorization': 'Bearer $accessToken', 'Content-Type': 'application/json'},
        body: jsonEncode({'stepCount': steps}),
      ).timeout(const Duration(seconds: 10));
    } catch (e) { debugPrint('[API Error] $e'); }
  }

}
