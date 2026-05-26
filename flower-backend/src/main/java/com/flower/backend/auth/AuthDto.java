// [기능 ID: AUTH-01~06] [명세 근거: PRD §4.0 / API Spec §2.1~2.6]
package com.flower.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Builder;

public class AuthDto {

    // 일반 회원가입/로그인 제거됨 (소셜 전용)
    // OAuthRequest(code flow)는 카카오 SDK 흐름 전환으로 제거됨 — AuthController.oauthKakaoToken 참고

    // ─── 소셜 신규 가입 시 프로필 설정 요청 ──────────────────────────────
    @Getter
    public static class ProfileSetupRequest {
        @NotBlank
        private String tempToken;

        @NotBlank
        @Size(min = 2, max = 10)
        private String nickname;

        private String profileImageUrl; // Oracle Cloud Storage URL (선택)
    }

    // ─── 로그인 성공 응답 (기존 유저) ────────────────────────────────────
    @Getter
    @Builder
    public static class LoginResponse {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;       // Access Token 만료까지 남은 시간(초)
        private UserInfo user;
    }

    // ─── 소셜 신규 유저 응답 (프로필 설정 필요) ──────────────────────────
    @Getter
    @Builder
    public static class OAuthNewUserResponse {
        @JsonProperty("isNewUser")
        private boolean isNewUser;
        private String tempToken;
        private String provider;
    }

    // ─── 토큰 갱신 요청 ──────────────────────────────────────────────────
    @Getter
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }

    // ─── 토큰 갱신 응답 ──────────────────────────────────────────────────
    @Getter
    @Builder
    public static class RefreshResponse {
        private String accessToken;
        private long expiresIn;
    }

    // ─── 유저 기본 정보 ───────────────────────────────────────────────────
    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }
}
