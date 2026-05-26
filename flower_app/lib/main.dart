import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:geolocator/geolocator.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';
import 'screens/login_screen.dart';
import 'screens/main_screen.dart';
import 'screens/profile_setup_screen.dart';
import 'services/api_client.dart';
import 'services/step_notification_service.dart';
import 'theme/season_theme.dart';

final FlutterLocalNotificationsPlugin _localNotifications =
    FlutterLocalNotificationsPlugin();

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // UI 분기 결정에 필요한 최소 작업만 동기로 (.env + 토큰 체크)
  await dotenv.load(fileName: ".env");

  // 카카오 SDK 초기화 (네이티브 + JS 키, .env에서 로드)
  KakaoSdk.init(
    nativeAppKey: dotenv.env['KAKAO_NATIVE_APP_KEY'] ?? '',
    javaScriptAppKey: dotenv.env['KAKAO_JAVASCRIPT_APP_KEY'] ?? '',
  );

  // 디버그용: 실제 키 해시 출력 → 카카오 콘솔에 이 값 등록 필요
  // (콘솔의 키 해시랑 안 맞으면 KakaoAuthException keyHash validation failed)
  // ignore: avoid_print
  print('[Kakao] keyHash to register in console: ${await KakaoSdk.origin}');

  final prefs = await SharedPreferences.getInstance();
  final String? token = prefs.getString('accessToken');

  bool hasToken = false;
  if (token != null && token.isNotEmpty) {
    if (_isTokenExpired(token)) {
      await prefs.remove('accessToken');
      await prefs.remove('refreshToken');
    } else {
      hasToken = true;
    }
  }

  // UI 먼저 띄움 (검은 화면 시간 최소화)
  runApp(OurTApp(hasToken: hasToken));

  // 무거운 초기화는 UI가 뜨고 난 뒤 백그라운드로 실행
  _initBackgroundTasks(prefs, hasToken);
}

Future<void> _initBackgroundTasks(
  SharedPreferences prefs,
  bool hasToken,
) async {
  try {
    await Firebase.initializeApp();
  } catch (_) {}
  await _initLocalNotifications();
  await StepNotificationService.initialize();
  await _initFcm(prefs); // 네트워크 의존 — fire & forget OK
  await _requestLocationPermission();
  if (hasToken) await _sendLocationToServer(prefs);
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
    return DateTime.fromMillisecondsSinceEpoch(
      exp * 1000,
    ).isBefore(DateTime.now());
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
  await _localNotifications.initialize(
    const InitializationSettings(android: android),
  );
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
            'ourt_channel',
            'OurT 알림',
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
    await ApiClient.dio.post(
      '/api/v1/auth/location',
      data: <String, dynamic>{
        'latitude': pos.latitude,
        'longitude': pos.longitude,
      },
    );
  } catch (_) {}
}

class OurTApp extends StatefulWidget {
  final bool hasToken;

  const OurTApp({super.key, this.hasToken = false});

  @override
  State<OurTApp> createState() => _OurTAppState();
}

class _OurTAppState extends State<OurTApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  bool _expiredHandled = false;

  @override
  void initState() {
    super.initState();
    // refresh도 실패한 401 → 토큰 정리 + 로그인 화면 강제 이동
    ApiClient.onSessionExpired = _handleSessionExpired;
  }

  Future<void> _handleSessionExpired() async {
    if (_expiredHandled) return;
    _expiredHandled = true;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove('accessToken');
      await prefs.remove('refreshToken');
      _navigatorKey.currentState?.pushNamedAndRemoveUntil(
        '/login',
        (_) => false,
      );
    } finally {
      // 다음 만료에도 동작하도록 짧게 후 잠금 해제
      Future<void>.delayed(const Duration(seconds: 2), () {
        _expiredHandled = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return MaterialApp(
      title: 'OurT',
      debugShowCheckedModeBanner: false,
      theme: colors.toThemeData(),
      navigatorKey: _navigatorKey,
      home: widget.hasToken ? const MainScreen() : const LoginScreen(),
      routes: {
        '/login': (context) => const LoginScreen(),
        '/profile-setup': (context) => const ProfileSetupScreen(),
        '/main': (context) => const MainScreen(),
      },
    );
  }
}
