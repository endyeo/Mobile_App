// [기능 ID: AUTH-02,04] [명세 근거: PRD §4.0 / API Spec §2.5]
package com.flower.backend.auth;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 OAuth 서버와의 실제 HTTP 통신을 담당하는 서비스.
 * SDK가 발급한 access token으로 사용자 정보를 조회한다.
 */
@Slf4j
@Service
public class OAuthService {

    private final AuthService authService;
    private final RestTemplate restTemplate;

    // RestTemplate을 생성자로 주입받아 테스트 시 MockRestServiceServer 연결 가능
    public OAuthService(AuthService authService, RestTemplate restTemplate) {
        this.authService = authService;
        this.restTemplate = restTemplate;
    }

    // ─── 카카오 OAuth 처리 ───────────────────────────────────────────────

    /**
     * 카카오 SDK가 직접 받은 access token으로 사용자 식별 + JWT 발급.
     * (구 code flow 제거됨 — 카카오톡 SSO를 안정적으로 처리하려면 SDK 흐름 사용)
     */
    public Object processKakaoAccessToken(String kakaoAccessToken) {
        KakaoUserInfo userInfo = getKakaoUserInfo(kakaoAccessToken);

        log.info("[OAuth] 카카오 로그인 시도 (SDK token) - providerId: {}", userInfo.getId());

        String nickname = (userInfo.getProperties() != null) ? userInfo.getProperties().getNickname() : "사용자";
        return authService.processOAuth(
            nickname,
            User.Provider.KAKAO,
            String.valueOf(userInfo.getId())
        );
    }

    private KakaoUserInfo getKakaoUserInfo(String accessToken) {
        String userInfoUrl = "https://kapi.kakao.com/v2/user/me";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<KakaoUserInfo> response = restTemplate.exchange(
            userInfoUrl, HttpMethod.GET, new HttpEntity<>(headers), KakaoUserInfo.class
        );

        if (response.getBody() == null) {
            throw new AuthException("OAUTH_UPSTREAM_ERROR", "카카오 유저 정보 조회에 실패했습니다.");
        }
        return response.getBody();
    }

    // ─── 내부 응답 매핑용 DTO ────────────────────────────────────────────

    @Getter
    private static class KakaoUserInfo {
        private Long id;
        private KakaoProperties properties;
    }

    @Getter
    private static class KakaoProperties {
        private String nickname;
    }
}
