import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../theme/season_theme.dart';
import '../services/auth_api_service.dart';

class ProfileSetupScreen extends StatefulWidget {
  const ProfileSetupScreen({super.key});

  @override
  State<ProfileSetupScreen> createState() => _ProfileSetupScreenState();
}

class _ProfileSetupScreenState extends State<ProfileSetupScreen> {
  final _nicknameController = TextEditingController();
  String? _profileImageUrl;
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void dispose() {
    _nicknameController.dispose();
    super.dispose();
  }

  Future<void> _pickProfileImage() async {
    // TODO: image_picker 패키지로 이미지 선택 후 Oracle Cloud Storage에 업로드
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('이미지 업로드 기능은 추후 구현 예정입니다.')),
    );
  }

  Future<void> _submitProfile() async {
    final nickname = _nicknameController.text.trim();

    if (nickname.length < 2 || nickname.length > 10) {
      setState(() => _errorMessage = '닉네임은 2~10자로 입력해 주세요.');
      return;
    }

    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    try {
      final prefs = await SharedPreferences.getInstance();
      final tempToken = prefs.getString('tempToken') ?? '';

      if (tempToken.isEmpty) {
        setState(() {
          _errorMessage = '인증 정보가 만료되었습니다. 다시 로그인해 주세요.';
          _isLoading = false;
        });
        return;
      }

      final result = await AuthApiService.setupProfile(
        tempToken: tempToken,
        nickname: nickname,
        profileImageUrl: _profileImageUrl,
      );

      if (!mounted) return;

      if (result['success'] == true) {
        final data = result['data'];
        final accessToken = data['accessToken'] ?? '';
        await prefs.setString('accessToken', accessToken);
        await prefs.setString('refreshToken', data['refreshToken'] ?? '');
        await prefs.remove('tempToken');

        // FCM 토큰 백엔드에 전송
        final fcmToken = prefs.getString('fcmToken');
        if (fcmToken != null && accessToken.isNotEmpty) {
          AuthApiService.saveFcmToken(
            accessToken: accessToken,
            fcmToken: fcmToken,
          );
        }

        if (!mounted) return;
        Navigator.pushReplacementNamed(context, '/main');
      } else {
        final errorMsg = result['error']?['message'] ?? '가입에 실패했습니다.';
        setState(() => _errorMessage = errorMsg);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _errorMessage = '오류가 발생했습니다: $e');
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
              const Spacer(flex: 2),

              Text(
                '프로필을 설정해 주세요',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: colors.primary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '다른 사용자에게 보여질 이름과 사진이에요.',
                style: TextStyle(fontSize: 14, color: Colors.grey[600]),
              ),

              const SizedBox(height: 40),

              GestureDetector(
                onTap: _pickProfileImage,
                child: Stack(
                  children: [
                    CircleAvatar(
                      radius: 52,
                      backgroundColor: colors.primary.withOpacity(0.15),
                      backgroundImage: _profileImageUrl != null
                          ? NetworkImage(_profileImageUrl!)
                          : null,
                      child: _profileImageUrl == null
                          ? Icon(
                              Icons.person_rounded,
                              size: 52,
                              color: colors.primary.withOpacity(0.6),
                            )
                          : null,
                    ),
                    Positioned(
                      bottom: 0,
                      right: 0,
                      child: Container(
                        width: 32,
                        height: 32,
                        decoration: BoxDecoration(
                          color: colors.primary,
                          shape: BoxShape.circle,
                          border: Border.all(color: Colors.white, width: 2),
                        ),
                        child: const Icon(
                          Icons.camera_alt_rounded,
                          size: 16,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 32),

              TextField(
                controller: _nicknameController,
                maxLength: 10,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
                decoration: InputDecoration(
                  hintText: '닉네임 (2~10자)',
                  hintStyle: TextStyle(color: Colors.grey[400]),
                  counterText: '',
                  filled: true,
                  fillColor: Colors.white,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide(color: colors.accent),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide(color: colors.accent.withOpacity(0.5)),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide(color: colors.primary, width: 2),
                  ),
                ),
              ),

              if (_errorMessage != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(
                    _errorMessage!,
                    style: const TextStyle(color: Colors.red, fontSize: 14),
                    textAlign: TextAlign.center,
                  ),
                ),

              const Spacer(flex: 2),

              SizedBox(
                width: double.infinity,
                height: 54,
                child: ElevatedButton(
                  onPressed: _isLoading ? null : _submitProfile,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: colors.primary,
                    foregroundColor: colors.buttonText,
                    disabledBackgroundColor: colors.primary.withOpacity(0.4),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                    elevation: 2,
                  ),
                  child: _isLoading
                      ? const SizedBox(
                          width: 24,
                          height: 24,
                          child: CircularProgressIndicator(
                            strokeWidth: 2.5,
                            color: Colors.white,
                          ),
                        )
                      : const Text(
                          '가입 완료하고 시작하기',
                          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                        ),
                ),
              ),

              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }
}
