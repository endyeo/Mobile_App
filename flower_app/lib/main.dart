import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
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
  final bool hasToken = token != null && token.isNotEmpty;

  // FCM 토큰 받아서 저장
  await _initFcm(prefs);

  runApp(OurTApp(hasToken: hasToken));
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
