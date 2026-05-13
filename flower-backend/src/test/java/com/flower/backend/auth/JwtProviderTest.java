package com.flower.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JWT 토큰 발급 및 검증 테스트")
class JwtProviderTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        // 테스트용 시크릿 키 (32자 이상)
        jwtProvider = new JwtProvider(
            "test-secret-key-must-be-at-least-32-chars!!",
            3600L,   // Access Token: 1시간
            2592000L // Refresh Token: 30일
        );
    }

    @Test
    @DisplayName("Access Token 발급 → 검증 성공")
    void generateAndValidateAccessToken() {
        String token = jwtProvider.generateAccessToken(1L);

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Refresh Token 발급 → 검증 성공")
    void generateAndValidateRefreshToken() {
        String token = jwtProvider.generateRefreshToken(1L);

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("소셜 신규 유저 Temp Token 발급 → provider/providerId 추출 성공")
    void generateAndParseTempToken() {
        String token = jwtProvider.generateTempToken("KAKAO", "kakao-provider-id-123");

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getProvider(token)).isEqualTo("KAKAO");
        assertThat(jwtProvider.getProviderId(token)).isEqualTo("kakao-provider-id-123");
    }

    @Test
    @DisplayName("변조된 토큰 → 검증 실패")
    void invalidToken_shouldFail() {
        String fakeToken = "this.is.a.fake.token";
        assertThat(jwtProvider.validateToken(fakeToken)).isFalse();
    }

    @Test
    @DisplayName("빈 문자열 토큰 → 검증 실패")
    void emptyToken_shouldFail() {
        assertThat(jwtProvider.validateToken("")).isFalse();
    }
}
