import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import '../api_config.dart';

/// 백엔드 API와 OAuth 흐름을 관리하는 서비스.
class AuthApiService {
  static String get baseUrl =>
      '${ApiConfig.backendBaseUrl(androidEmulator: defaultTargetPlatform == TargetPlatform.android)}/api/v1/auth';

  // 모바일 OAuth 콜백 딥링크 스킴
  static const String callbackUrlScheme = 'ourt';
  static const String callbackUrl = 'https://ourt.kro.kr/oauth/callback';

  // ─── 카카오 인증 URL 생성 ───────────────────────────────────

  static String getKakaoAuthUrl(String clientId) {
    return 'https://kauth.kakao.com/oauth/authorize'
        '?client_id=$clientId'
        '&redirect_uri=${Uri.encodeComponent(callbackUrl)}'
        '&response_type=code';
  }

  // ─── auth_code를 백엔드에 전송 ─────────────────────────────

  static Future<Map<String, dynamic>> sendAuthCode({
    required String provider,
    required String authCode,
  }) async {
    final url = '$baseUrl/oauth/$provider';
    final response = await http.post(
      Uri.parse(url),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'authCode': authCode,
        'redirectUri': callbackUrl,
      }),
    );

    return jsonDecode(response.body);
  }

  // ─── FCM 토큰 저장 ────────────────────────────────────────
  static Future<void> saveFcmToken({
    required String accessToken,
    required String fcmToken,
  }) async {
    await http.post(
      Uri.parse('$baseUrl/fcm-token'),
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer $accessToken',
      },
      body: jsonEncode({'fcmToken': fcmToken}),
    );
  }

  // ─── 프로필 설정 (신규 유저) ────────────────────────────────

  static Future<Map<String, dynamic>> setupProfile({
    required String tempToken,
    required String nickname,
    String? profileImageUrl,
  }) async {
    final response = await http.post(
      Uri.parse('$baseUrl/profile-setup'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({
        'tempToken': tempToken,
        'nickname': nickname,
        'profileImageUrl': profileImageUrl,
      }),
    );

    return jsonDecode(response.body);
  }
}
