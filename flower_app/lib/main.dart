import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:geolocator/geolocator.dart';
import 'screens/login_screen.dart';
import 'screens/main_screen.dart';
import 'screens/profile_setup_screen.dart';
import 'theme/season_theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: ".env");
  await Firebase.initializeApp();

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

  await _initFcm(prefs);
  await _requestLocationPermission();

  runApp(OurTApp(hasToken: hasToken));
}

bool _isTokenExpired(String token) {
  try {
    final parts = token.split('.');
    if (parts.length != 3) return true;
    final payload = base64Url.decode(base64Url.normalize(parts[1]));
    final data = jsonDecode(utf8.decode(payload)) as Map<String, dynamic>;
    final exp = data['exp'] as int?;
    if (exp == null) return true;
    return DateTime.fromMillisecondsSinceEpoch(exp * 1000).isBefore(DateTime.now());
  } catch (_) {
    return true;
  }
}

Future<void> _requestLocationPermission() async {
  try {
    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      await Geolocator.requestPermission();
    }
  } catch (_) {}
}

Future<void> _initFcm(SharedPreferences prefs) async {
  try {
    final messaging = FirebaseMessaging.instance;

    // 알림 권한 요청
    await messaging.requestPermission();

    // FCM 토큰 받기
    final fcmToken = await messaging.getToken();
    if (fcmToken != null) {
      await prefs.setString('fcmToken', fcmToken);
    }
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
