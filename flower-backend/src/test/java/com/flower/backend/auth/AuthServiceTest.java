package com.flower.backend.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtProvider jwtProvider;

    // ─── 카카오 OAuth 로그인 ─────────────────────────────────────────────
    @Test
    @DisplayName("카카오 기존 회원 재로그인 → 즉시 토큰 반환")
    void processOAuth_existingKakaoUser_returnsLoginResponse() {
        User mockUser = User.createOAuthUser("꽃향기", null, User.Provider.KAKAO, "kakao-456");
        given(userRepository.findByProviderAndProviderId(User.Provider.KAKAO, "kakao-456"))
            .willReturn(Optional.of(mockUser));
        given(jwtProvider.generateAccessToken(any())).willReturn("access_token");
        given(jwtProvider.generateRefreshToken(any())).willReturn("refresh_token");
        given(jwtProvider.getAccessTokenValidSeconds()).willReturn(3600L);

        Object result = authService.processOAuth("꽃향기", User.Provider.KAKAO, "kakao-456");

        assertThat(result).isInstanceOf(AuthDto.LoginResponse.class);
        AuthDto.LoginResponse response = (AuthDto.LoginResponse) result;
        assertThat(response.getAccessToken()).isEqualTo("access_token");
    }

    @Test
    @DisplayName("카카오 신규 회원 로그인 → tempToken 반환")
    void processOAuth_newKakaoUser_returnsTempToken() {
        given(userRepository.findByProviderAndProviderId(User.Provider.KAKAO, "kakao-999"))
            .willReturn(Optional.empty());
        given(jwtProvider.generateTempToken("KAKAO", "kakao-999")).willReturn("temp-token-xyz");

        Object result = authService.processOAuth("새싹", User.Provider.KAKAO, "kakao-999");

        assertThat(result).isInstanceOf(AuthDto.OAuthNewUserResponse.class);
        AuthDto.OAuthNewUserResponse response = (AuthDto.OAuthNewUserResponse) result;
        assertThat(response.isNewUser()).isTrue();
        assertThat(response.getTempToken()).isEqualTo("temp-token-xyz");
        assertThat(response.getProvider()).isEqualTo("KAKAO");
    }

    // ─── 토큰 갱신 ───────────────────────────────────────────────────────
    @Test
    @DisplayName("유효한 Refresh Token → 새 Access Token 반환")
    void refresh_success() {
        var request = new AuthDto.RefreshRequest();
        setField(request, "refreshToken", "valid_refresh_token");

        given(jwtProvider.validateToken("valid_refresh_token")).willReturn(true);
        given(jwtProvider.getUserId("valid_refresh_token")).willReturn(1L);
        given(jwtProvider.generateAccessToken(1L)).willReturn("new_access_token");
        given(jwtProvider.getAccessTokenValidSeconds()).willReturn(3600L);

        var response = authService.refresh(request);

        assertThat(response.getAccessToken()).isEqualTo("new_access_token");
    }

    @Test
    @DisplayName("만료된 Refresh Token → INVALID_REFRESH_TOKEN 예외")
    void refresh_expiredToken_throwsException() {
        var request = new AuthDto.RefreshRequest();
        setField(request, "refreshToken", "expired_token");

        given(jwtProvider.validateToken("expired_token")).willReturn(false);

        assertThatThrownBy(() -> authService.refresh(request))
            .isInstanceOf(AuthException.class)
            .hasFieldOrPropertyWithValue("errorCode", "INVALID_REFRESH_TOKEN");
    }

    // ─── 로그아웃 ────────────────────────────────────────────────────────
    @Test
    @DisplayName("로그아웃 성공 → FCM 토큰 null 초기화 (푸시 알림 차단)")
    void logout_success_clearsFcmToken() {
        User mockUser = User.createOAuthUser("꽃향기", null, User.Provider.KAKAO, "kakao-123");
        mockUser.updateFcmToken("device-fcm-token-abc");

        given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
        given(userRepository.save(any())).willReturn(mockUser);

        authService.logout(1L);

        assertThat(mockUser.getFcmToken()).isNull();
        verify(userRepository).save(mockUser);
    }

    @Test
    @DisplayName("존재하지 않는 유저 로그아웃 → 에러 없이 무시됨")
    void logout_nonExistentUser_silentlyIgnored() {
        given(userRepository.findById(999L)).willReturn(Optional.empty());

        assertThatCode(() -> authService.logout(999L))
            .doesNotThrowAnyException();
    }

    // ─── 테스트용 필드 주입 헬퍼 ─────────────────────────────────────────
    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("테스트 필드 설정 실패: " + fieldName, e);
        }
    }
}
