package com.flower.backend.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController 단위 테스트")
class AuthControllerTest {

    @InjectMocks
    private AuthController authController;

    @Mock
    private AuthService authService;

    // ─── 회원가입 ────────────────────────────────────────────────────────
    @Test
    @DisplayName("회원가입 성공 → 201 반환")
    void signup_success_returns201() throws Exception {
        var request = new AuthDto.SignupRequest();
        setField(request, "email", "new@flower.com");
        setField(request, "password", "test1234ab");
        setField(request, "nickname", "꽃향기");

        var mockResponse = AuthDto.LoginResponse.builder()
            .accessToken("access_token")
            .refreshToken("refresh_token")
            .expiresIn(3600L)
            .user(AuthDto.UserInfo.builder().userId(1L).nickname("꽃향기").build())
            .build();

        given(authService.signup(any())).willReturn(mockResponse);

        var result = authController.signup(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsKey("data");
    }

    @Test
    @DisplayName("이메일 중복 회원가입 → 409 반환")
    void signup_duplicateEmail_returns409() throws Exception {
        var request = new AuthDto.SignupRequest();
        setField(request, "email", "dup@flower.com");
        setField(request, "password", "test1234ab");
        setField(request, "nickname", "중복닉");

        // EMAIL_ALREADY_EXISTS 에러 코드로 @ExceptionHandler 직접 호출 → 409 응답 검증
        AuthException ex = new AuthException("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.");
        var result = authController.handleAuthException(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("success", false);
    }

    // ─── 로그인 ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("로그인 성공 → 200 반환")
    void login_success_returns200() throws Exception {
        var request = new AuthDto.LoginRequest();
        setField(request, "email", "test@flower.com");
        setField(request, "password", "test1234");

        var mockResponse = AuthDto.LoginResponse.builder()
            .accessToken("access_token")
            .refreshToken("refresh_token")
            .expiresIn(3600L)
            .user(AuthDto.UserInfo.builder().userId(1L).nickname("꽃향기").build())
            .build();

        given(authService.login(any())).willReturn(mockResponse);

        var result = authController.login(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("success", true);
    }

    @Test
    @DisplayName("잘못된 비밀번호 → 401 반환")
    void login_wrongPassword_returns401() throws Exception {
        var request = new AuthDto.LoginRequest();
        setField(request, "email", "test@flower.com");
        setField(request, "password", "wrongpw");

        // @ExceptionHandler를 직접 호출하여 에러 응답 검증
        var ex = new AuthException("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        var result = authController.handleAuthException(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("success", false);
    }

    // ─── 토큰 갱신 ───────────────────────────────────────────────────────
    @Test
    @DisplayName("토큰 갱신 성공 → 200 반환")
    void refresh_success_returns200() throws Exception {
        var request = new AuthDto.RefreshRequest();
        setField(request, "refreshToken", "valid_refresh_token");

        var mockResponse = AuthDto.RefreshResponse.builder()
            .accessToken("new_access_token")
            .expiresIn(3600L)
            .build();

        given(authService.refresh(any())).willReturn(mockResponse);

        var result = authController.refresh(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) result.getBody();
        assertThat(body).containsEntry("success", true);
    }

    @Test
    @DisplayName("만료된 토큰 갱신 → 401 반환")
    void refresh_expiredToken_returns401() throws Exception {
        var request = new AuthDto.RefreshRequest();
        setField(request, "refreshToken", "expired_token");

        // @ExceptionHandler를 직접 호출하여 에러 응답 검증
        var ex = new AuthException("INVALID_REFRESH_TOKEN", "유효하지 않은 Refresh Token입니다.");
        var result = authController.handleAuthException(ex);

        assertThat(result.getStatusCode().value()).isEqualTo(401);
    }

    // ─── 테스트용 리플렉션 헬퍼 ─────────────────────────────────────────
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }
}
