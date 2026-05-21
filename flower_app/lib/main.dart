import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'screens/login_screen.dart';
import 'screens/main_screen.dart';
import 'screens/profile_setup_screen.dart';
import 'services/step_notification_service.dart';
import 'theme/season_theme.dart';
import 'api_config.dart';

final FlutterLocalNotificationsPlugin _localNotifications = FlutterLocalNotificationsPlugin();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: ".env");
  try { await Firebase.initializeApp(); } catch (_) {}

  final prefs = await SharedPreferences.getInstance();
  final String? token = prefs.getString('accessToken');

  // 토큰 만료 시 자동 로그아웃
  bool hasToken = false;
  if (token != null && token.isNotEmpty) {
    if (_isTokenExpired(token)) {
      await prefs.remove('accessToken');
      await prefs.remove('refreshToken');
    } else {
      hasToken = true;
    }
  }

  await _initLocalNotifications();
  await StepNotificationService.initialize();
  await _initFcm(prefs);
  await _requestLocationPermission();
  if (hasToken) await _sendLocationToServer(prefs);

  runApp(OurTApp(hasToken: hasToken));
}

bool _isTokenExpired(String token) {
  try {
    final parts = token.split('.');
    if (parts.length != 3) {
      debugPrint('[Auth] JWT 형식 오류: parts=${parts.length}');
      return true;
    }
    final payload = base64Url.decode(base64Url.normalize(parts[1]));
    final data = jsonDecode(utf8.decode(payload)) as Map<String, dynamic>;
    final exp = data['exp'] as int?;
    if (exp == null) {
      debugPrint('[Auth] JWT exp 필드 없음');
      return true;
    }
    return DateTime.fromMillisecondsSinceEpoch(exp * 1000).isBefore(DateTime.now());
  } catch (e) {
    debugPrint('[Auth] JWT 파싱 실패: $e');
    return true;
  }
}

Future<void> _requestLocationPermission() async {
  try {
    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    // Android 10+ : whileInUse 후 항상 허용은 설정에서만 가능 → 별도 요청 불필요
    // 항상 허용 여부는 _sendLocationToServer에서 체크
  } catch (_) {}
}

Future<void> _initLocalNotifications() async {
  const android = AndroidInitializationSettings('@mipmap/ic_launcher');
  await _localNotifications.initialize(const InitializationSettings(android: android));
}

Future<void> _initFcm(SharedPreferences prefs) async {
  try {
    final messaging = FirebaseMessaging.instance;
    await messaging.requestPermission();

    final fcmToken = await messaging.getToken();
    if (fcmToken != null) {
      await prefs.setString('fcmToken', fcmToken);
    }

    // 포그라운드 알림 처리
    FirebaseMessaging.onMessage.listen((RemoteMessage message) {
      final notification = message.notification;
      if (notification == null) return;
      _localNotifications.show(
        notification.hashCode,
        notification.title,
        notification.body,
        const NotificationDetails(
          android: AndroidNotificationDetails(
            'ourt_channel', 'OurT 알림',
            importance: Importance.high,
            priority: Priority.high,
          ),
        ),
      );
    });
  } catch (_) {}
}

Future<void> _sendLocationToServer(SharedPreferences prefs) async {
  try {
    final permission = await Geolocator.checkPermission();
    // 항상 허용(always)인 사용자만 위치를 서버에 저장 → 근처 알림 대상
    if (permission != LocationPermission.always) return;
    final pos = await Geolocator.getCurrentPosition(
      locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
    );
    final token = prefs.getString('accessToken') ?? '';
    if (token.isEmpty) return;
    await http.post(
      Uri.parse('${ApiConfig.backendBaseUrl()}/api/v1/auth/location'),
      headers: {'Authorization': 'Bearer $token', 'Content-Type': 'application/json'},
      body: jsonEncode({'latitude': pos.latitude, 'longitude': pos.longitude}),
    ).timeout(const Duration(seconds: 5));
  } catch (_) {}
}

class OurTApp extends StatelessWidget {
  final bool hasToken;

  const OurTApp({super.key, this.hasToken = false});

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return MaterialApp(
      title: 'OurT',
      debugShowCheckedModeBanner: false,
      theme: colors.toThemeData(),
      home: hasToken ? const MainScreen() : const LoginScreen(),
      routes: {
        '/login': (context) => const LoginScreen(),
        '/profile-setup': (context) => const ProfileSetupScreen(),
        '/main': (context) => const MainScreen(),
      },
    );
  }
}
