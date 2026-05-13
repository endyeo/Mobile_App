import 'package:flutter_dotenv/flutter_dotenv.dart';

class ApiConfig {
  ApiConfig._();

  static String get kakaoMapKey => dotenv.env['KAKAO_MAP_KEY'] ?? '';
  static String get nongsaroKey => dotenv.env['NONGSARO_API_KEY'] ?? '';
  static String get tourApiKey => dotenv.env['TOUR_API_KEY'] ?? '';

  static String backendBaseUrl({bool androidEmulator = false}) {
    final url = dotenv.env['BACKEND_URL'];
    if (url != null && url.isNotEmpty) return url;
    if (androidEmulator) return 'http://10.0.2.2:8080';
    return 'http://localhost:8080';
  }

  static String mapApiBaseUrl({bool androidEmulator = false}) {
    return '${backendBaseUrl(androidEmulator: androidEmulator)}/api/v1';
  }

  static bool get isKakaoKeySet => kakaoMapKey.isNotEmpty;
  static bool get isNongsaroKeySet => nongsaroKey.isNotEmpty;
  static bool get isTourApiKeySet => tourApiKey.isNotEmpty;
}
