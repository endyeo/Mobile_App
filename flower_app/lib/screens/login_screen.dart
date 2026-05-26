import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';
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

  /// 카카오톡 앱 우선, 없으면 카카오 계정 웹 로그인.
  /// SDK가 카카오톡 SSO 흐름·에러 처리·콜백 deep link를 모두 안정적으로 다룬다.
  Future<OAuthToken> _runKakaoSdkLogin() async {
    if (await isKakaoTalkInstalled()) {
      debugPrint('[Login] 카카오톡 앱으로 로그인 시도');
      try {
        return await UserApi.instance.loginWithKakaoTalk();
      } catch (e) {
        // 사용자가 카카오톡 로그인을 취소하면 PlatformException(CANCELED)
        if (e is PlatformException && e.code == 'CANCELED') rethrow;
        // 그 외 (카카오톡 미로그인 등) → 계정 웹 로그인 폴백
        debugPrint('[Login] 카카오톡 로그인 실패, 계정 로그인으로 폴백: $e');
        return await UserApi.instance.loginWithKakaoAccount();
      }
    }
    debugPrint('[Login] 카카오톡 미설치 → 계정 로그인');
    return await UserApi.instance.loginWithKakaoAccount();
  }

  Future<void> _loginWithKakao() async {
    debugPrint('[Login] _loginWithKakao 시작');

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final OAuthToken token = await _runKakaoSdkLogin();
      debugPrint(
        '[Login] SDK access token 발급 OK (length=${token.accessToken.length})',
      );

      // 백엔드에 access token 전송 → 사용자 식별 + JWT 발급
      debugPrint('[Login] 백엔드 sendKakaoAccessToken 호출');
      final response = await AuthApiService.sendKakaoAccessToken(
        token.accessToken,
      );
      debugPrint(
        '[Login] 백엔드 응답 success=${response['success']} isNewUser=${response['data']?['isNewUser']}',
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
          if (user?['nickname'] != null)
            await prefs.setString('nickname', user!['nickname']);
          if (user?['profileImageUrl'] != null)
            await prefs.setString('profileImageUrl', user!['profileImageUrl']);

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
    } catch (e, st) {
      debugPrint('[Login] 예외: ${e.runtimeType}: $e');
      debugPrint('[Login] 스택트레이스:\n$st');
      if (mounted) {
        final bool canceled = e is PlatformException && e.code == 'CANCELED';
        setState(
          () => _errorMessage = canceled
              ? null // 사용자 취소는 에러 메시지 안 띄움
              : '로그인 중 오류가 발생했습니다. 다시 시도해주세요.',
        );
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  /// 개발자 즉시 로그인 — kDebugMode 빌드에만 노출.
  /// 폰별 UUID를 SharedPreferences에 영구 저장 → 같은 폰에서 매번 같은 계정.
  Future<void> _loginAsDev() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final prefs = await SharedPreferences.getInstance();
      String? devId = prefs.getString('devTestUserId');
      if (devId == null || devId.isEmpty) {
        devId = const Uuid().v4();
        await prefs.setString('devTestUserId', devId);
      }
      debugPrint('[Login] dev-login 시도 devId=$devId');

      final response = await AuthApiService.devLogin(devId);
      if (!mounted) return;

      if (response['success'] != true) {
        final errorField = response['error'];
        final msg = errorField is Map
            ? errorField['message']?.toString() ?? '개발자 로그인 실패'
            : '개발자 로그인 실패';
        setState(() => _errorMessage = msg);
        return;
      }

      final data = response['data'];
      if (data['isNewUser'] == true) {
        await prefs.setString('tempToken', data['tempToken'] ?? '');
        if (!mounted) return;
        Navigator.pushReplacementNamed(context, '/profile-setup');
      } else {
        final accessToken = data['accessToken'] ?? '';
        await prefs.setString('accessToken', accessToken);
        await prefs.setString('refreshToken', data['refreshToken'] ?? '');
        final user = data['user'] as Map<String, dynamic>?;
        if (user?['nickname'] != null)
          await prefs.setString('nickname', user!['nickname']);
        if (user?['profileImageUrl'] != null)
          await prefs.setString('profileImageUrl', user!['profileImageUrl']);
        if (!mounted) return;
        Navigator.pushReplacementNamed(context, '/main');
      }
    } catch (e) {
      debugPrint('[Login] dev-login 예외: $e');
      if (mounted) {
        setState(() => _errorMessage = '개발자 로그인 실패: $e');
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

              // ── 디버그 빌드에서만 보이는 개발자 로그인 ──
              if (kDebugMode) ...[
                const SizedBox(height: 12),
                TextButton.icon(
                  onPressed: _isLoading ? null : _loginAsDev,
                  icon: const Icon(Icons.bug_report, size: 18),
                  label: const Text('개발자 로그인 (이 폰 전용)'),
                  style: TextButton.styleFrom(
                    foregroundColor: Colors.grey[600],
                  ),
                ),
              ],

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
          style: TextStyle(fontSize: 15, color: Colors.grey[600]),
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
