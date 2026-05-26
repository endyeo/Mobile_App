import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'api_client.dart';

/// 백엔드 API와 OAuth 흐름을 관리하는 서비스.
class AuthApiService {
  static const String _basePath = '/api/v1/auth';

  /// 카카오 SDK가 발급받은 access token으로 백엔드 로그인.
  /// code flow보다 카카오톡 SSO에 안정적.
  static Future<Map<String, dynamic>> sendKakaoAccessToken(
    String accessToken,
  ) async {
    final Response<dynamic> response = await ApiClient.dio.post(
      '$_basePath/oauth/kakao/token',
      data: <String, dynamic>{'accessToken': accessToken},
    );
    return (response.data as Map).cast<String, dynamic>();
  }

  /// 개발자 즉시 로그인. 폰별 영구 UUID로 자동 사용자 식별.
  /// 운영에서는 백엔드의 DEV_LOGIN_ENABLED 미설정으로 403.
  static Future<Map<String, dynamic>> devLogin(String devId) async {
    final Response<dynamic> response = await ApiClient.dio.post(
      '$_basePath/dev-login',
      data: <String, dynamic>{'devId': devId},
    );
    return (response.data as Map).cast<String, dynamic>();
  }

  // ─── FCM 토큰 저장 ────────────────────────────────────────
  static Future<void> saveFcmToken({
    required String accessToken,
    required String fcmToken,
  }) async {
    await ApiClient.dio.post(
      '$_basePath/fcm-token',
      data: <String, dynamic>{'fcmToken': fcmToken},
    );
  }

  // ─── 프로필 설정 (신규 유저) ────────────────────────────────
  /// tempToken으로 인증 → AuthInterceptor의 자동 access 첨부를 막기 위해
  /// Authorization 헤더를 명시 전달 (이미 있으면 인터셉터가 덮어쓰지 않음).
  static Future<Map<String, dynamic>> setupProfile({
    required String tempToken,
    required String nickname,
    String? profileImageUrl,
  }) async {
    final Response<dynamic> response = await ApiClient.dio.post(
      '$_basePath/profile-setup',
      data: <String, dynamic>{
        'tempToken': tempToken,
        'nickname': nickname,
        'profileImageUrl': profileImageUrl,
      },
      options: Options(
        headers: <String, dynamic>{'Authorization': 'Bearer $tempToken'},
      ),
    );
    return (response.data as Map).cast<String, dynamic>();
  }

  // ─── 닉네임 변경 ────────────────────────────────────────────
  static Future<String?> updateNickname(String nickname) async {
    try {
      final Response<dynamic> response = await ApiClient.dio.patch(
        '$_basePath/profile/nickname',
        data: <String, dynamic>{'nickname': nickname},
      );
      if (response.statusCode == 200 && response.data is Map) {
        final Map data = (response.data as Map)['data'] as Map;
        return data['nickname'] as String?;
      }
    } catch (e) {
      debugPrint('[Auth] 닉네임 변경 실패: $e');
    }
    return null;
  }

  // ─── 프로필 이미지 변경 ─────────────────────────────────────
  static Future<String?> updateProfileImage(File image) async {
    try {
      final FormData form = FormData.fromMap(<String, dynamic>{
        'image': await MultipartFile.fromFile(image.path),
      });
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/profile/image',
        data: form,
        options: Options(sendTimeout: const Duration(seconds: 30)),
      );
      if (response.statusCode == 200 && response.data is Map) {
        final Map data = (response.data as Map)['data'] as Map;
        return data['profileImageUrl'] as String?;
      }
    } catch (e) {
      debugPrint('[Auth] 프로필 이미지 변경 실패: $e');
    }
    return null;
  }

  // ─── 로그아웃 (서버: FCM 토큰 초기화) ───────────────────────
  /// 실패해도 클라이언트는 로그아웃 진행해야 하므로 예외는 던지지 않음.
  static Future<bool> logout({required String accessToken}) async {
    if (accessToken.isEmpty) return true;
    try {
      final Response<dynamic> response = await ApiClient.dio.post(
        '$_basePath/logout',
      );
      return response.statusCode == 200;
    } catch (e) {
      debugPrint('[Auth] 로그아웃 API 실패(무시하고 진행): $e');
      return false;
    }
  }
}
