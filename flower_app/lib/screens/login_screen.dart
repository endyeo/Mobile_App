import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/auth_api_service.dart';
import '../utils/location_permission_helper.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _isLoading = false;
  String? _errorMessage;

  Future<void> _loginWithKakao() async {
    final clientId = dotenv.env['KAKAO_REST_API_KEY'] ?? '';
    if (clientId.isEmpty) {
      setState(() => _errorMessage = 'KAKAO_REST_API_KEY가 설정되지 않았습니다.');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      // 시스템 브라우저에서 카카오 로그인 → 콜백 딥링크 수신
      final result = await FlutterWebAuth2.authenticate(
        url: AuthApiService.getKakaoAuthUrl(clientId),
        callbackUrlScheme: AuthApiService.callbackUrlScheme,
      );

      final code = Uri.parse(result).queryParameters['code'];
      if (code == null) {
        setState(() => _errorMessage = '인증 코드를 받지 못했습니다.');
        return;
      }

      // 백엔드에 code 전송
      final response = await AuthApiService.sendAuthCode(
        provider: 'kakao',
        authCode: code,
      );

      if (!mounted) return;

      if (response['success'] == true) {
        final data = response['data'];
        final prefs = await SharedPreferences.getInstance();

        if (data['isNewUser'] == true) {
          await prefs.setString('tempToken', data['tempToken'] ?? '');
          if (!mounted) return;
          Navigator.pushReplacementNamed(context, '/profile-setup');
        } else {
          final accessToken = data['accessToken'] ?? '';
          await prefs.setString('accessToken', accessToken);
          await prefs.setString('refreshToken', data['refreshToken'] ?? '');
          final user = data['user'] as Map<String, dynamic>?;
          if (user?['nickname'] != null) await prefs.setString('nickname', user!['nickname']);
          if (user?['profileImageUrl'] != null) await prefs.setString('profileImageUrl', user!['profileImageUrl']);

          // FCM 토큰 백엔드에 전송
          final fcmToken = prefs.getString('fcmToken');
          if (fcmToken != null && accessToken.isNotEmpty) {
            await AuthApiService.saveFcmToken(
              accessToken: accessToken,
              fcmToken: fcmToken,
            );
          }

          if (!mounted) return;
          await promptAlwaysLocation(context, firstTime: true);
          if (!mounted) return;
          Navigator.pushReplacementNamed(context, '/main');
        }
      } else {
        final errorField = response['error'];
        final msg = errorField is Map
            ? errorField['message']?.toString() ?? '로그인에 실패했습니다.'
            : response['message']?.toString() ?? '로그인에 실패했습니다.';
        setState(() => _errorMessage = msg);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = '로그인 중 오류가 발생했습니다.');
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = SeasonTheme.getColors();

    return Scaffold(
      backgroundColor: colors.background,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32.0),
          child: Column(
            children: [
              const Spacer(flex: 3),

              // ── 앱 로고 영역 ──
              _buildLogo(colors),

              const Spacer(flex: 2),

              // ── 카카오 로그인 버튼 ──
              _buildKakaoButton(),

              // ── 에러 메시지 ──
              if (_errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 16),
                  child: Text(
                    _errorMessage!,
                    style: const TextStyle(color: Colors.red, fontSize: 13),
                    textAlign: TextAlign.center,
                  ),
                ),

              const Spacer(flex: 2),

              // ── 하단 안내 문구 ──
              Text(
                '로그인 시 이용약관 및 개인정보 처리방침에 동의합니다.',
                style: TextStyle(color: Colors.grey[500], fontSize: 12),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildLogo(SeasonColors colors) {
    return Column(
      children: [
        Container(
          width: 100,
          height: 100,
          decoration: BoxDecoration(
            color: colors.primary.withOpacity(0.15),
            shape: BoxShape.circle,
          ),
          child: Icon(
            Icons.local_florist_rounded,
            size: 56,
            color: colors.primary,
          ),
        ),
        const SizedBox(height: 20),
        Text(
          'OurT',
          style: TextStyle(
            fontSize: 40,
            fontWeight: FontWeight.bold,
            color: colors.primary,
            letterSpacing: 2,
          ),
        ),
        const SizedBox(height: 8),
        Text(
          '${colors.name}의 꽃과 함께 산책해요',
          style: TextStyle(
            fontSize: 15,
            color: Colors.grey[600],
          ),
        ),
      ],
    );
  }

  // ── 카카오 버튼 (공식 제공 이미지 사용) ──
  Widget _buildKakaoButton() {
    if (_isLoading) {
      return const SizedBox(
        height: 52,
        child: Center(child: CircularProgressIndicator()),
      );
    }

    return GestureDetector(
      onTap: _loginWithKakao,
      child: Image.asset(
        'assets/images/kakao_login_medium_narrow.png',
        width: double.infinity,
        fit: BoxFit.fitWidth,
      ),
    );
  }
}
