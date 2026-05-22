import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api_config.dart';

/// 백엔드 API 공통 Dio 인스턴스 + Auth/Refresh 인터셉터.
///
/// 사용 패턴:
/// ```dart
/// final response = await ApiClient.dio.get('/api/v1/community/posts');
/// ```
///
/// - Authorization 헤더에 accessToken 자동 첨부
/// - 401 응답 시 refreshToken으로 새 access를 받아 원 요청 자동 재시도
/// - refresh도 실패하면 onSessionExpired 콜백 호출 (호스트 앱에서 로그인 화면 강제 이동 처리)
class ApiClient {
  ApiClient._();

  static final Dio dio = _build();

  /// 세션 만료 시 호출되는 콜백 (호스트 앱이 main에서 설정).
  /// 토큰 정리·로그인 화면 이동 등 UI 동작은 여기서 처리.
  static void Function()? onSessionExpired;

  static Dio _build() {
    final Dio instance = Dio(
      BaseOptions(
        baseUrl: ApiConfig.backendBaseUrl(
          androidEmulator: defaultTargetPlatform == TargetPlatform.android,
        ),
        connectTimeout: const Duration(seconds: 15),
        receiveTimeout: const Duration(seconds: 30),
        sendTimeout: const Duration(seconds: 30),
        headers: <String, dynamic>{'Content-Type': 'application/json'},
        // 401 등도 응답으로 받아서 인터셉터에서 처리
        validateStatus: (int? status) => status != null && status < 500,
      ),
    );
    instance.interceptors.add(_AuthInterceptor(instance));
    return instance;
  }
}

class _AuthInterceptor extends QueuedInterceptor {
  _AuthInterceptor(this._dio);

  final Dio _dio;
  // 동시에 여러 401이 와도 refresh는 한 번만 돌도록 락
  Future<bool>? _refreshing;

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    // multipart 요청은 자체 Content-Type을 보존
    if (options.data is FormData) {
      options.headers.remove('Content-Type');
    }
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String token = prefs.getString('accessToken') ?? '';
    if (token.isNotEmpty && !options.headers.containsKey('Authorization')) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  Future<void> onResponse(
    Response<dynamic> response,
    ResponseInterceptorHandler handler,
  ) async {
    // 200~499 범위 응답. 401만 refresh 시도.
    if (response.statusCode == 401 &&
        !_isAuthEndpoint(response.requestOptions)) {
      final bool refreshed = await _ensureRefresh();
      if (refreshed) {
        try {
          final Response<dynamic> retry = await _retryWithNewToken(
            response.requestOptions,
          );
          handler.resolve(retry);
          return;
        } catch (e) {
          debugPrint('[ApiClient] 재시도 실패: $e');
        }
      } else {
        _notifySessionExpired();
      }
    }
    handler.next(response);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    // validateStatus < 500이라 401은 보통 onResponse로 옴.
    // 네트워크 단절 등의 진짜 에러만 여기로 옴 → 그대로 전달.
    handler.next(err);
  }

  bool _isAuthEndpoint(RequestOptions options) {
    final String path = options.path;
    return path.contains('/auth/oauth/') ||
        path.contains('/auth/refresh') ||
        path.contains('/auth/profile-setup');
  }

  Future<bool> _ensureRefresh() {
    return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<bool> _doRefresh() async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final String refreshToken = prefs.getString('refreshToken') ?? '';
      if (refreshToken.isEmpty) return false;

      // 새 Dio 인스턴스로 호출 → 인터셉터 무한 루프 방지
      final Dio plain = Dio(BaseOptions(baseUrl: _dio.options.baseUrl));
      final Response<dynamic> response = await plain.post(
        '/api/v1/auth/refresh',
        data: <String, dynamic>{'refreshToken': refreshToken},
        options: Options(
          headers: <String, dynamic>{'Content-Type': 'application/json'},
          validateStatus: (int? status) => status != null && status < 500,
        ),
      );

      if (response.statusCode != 200) {
        debugPrint('[ApiClient] refresh 실패: ${response.statusCode}');
        return false;
      }
      final dynamic body = response.data;
      final dynamic data = body is Map ? body['data'] : null;
      final String? newAccess = (data is Map)
          ? data['accessToken'] as String?
          : null;
      if (newAccess == null || newAccess.isEmpty) return false;

      await prefs.setString('accessToken', newAccess);
      return true;
    } catch (e) {
      debugPrint('[ApiClient] refresh 예외: $e');
      return false;
    }
  }

  Future<Response<dynamic>> _retryWithNewToken(RequestOptions options) async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String token = prefs.getString('accessToken') ?? '';
    final RequestOptions newOptions = options.copyWith(
      headers: <String, dynamic>{
        ...options.headers,
        'Authorization': 'Bearer $token',
      },
    );
    return _dio.fetch(newOptions);
  }

  void _notifySessionExpired() {
    final void Function()? cb = ApiClient.onSessionExpired;
    if (cb != null) {
      // 콜백은 UI 이동을 포함할 수 있으므로 다음 frame에 호출
      scheduleMicrotask(cb);
    }
  }
}
