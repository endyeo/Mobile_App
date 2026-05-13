// [기능 ID: AUTH-02,04] [명세 근거: PRD §4.0 / API Spec §2.5]
package com.flower.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 카카오 OAuth 서버와의 실제 HTTP 통신을 담당하는 서비스.
 */
@Slf4j
@Service
public class OAuthService {

    private final OAuthProperties oAuthProperties;
    private final AuthService authService;
    private final RestTemplate restTemplate;

    // RestTemplate을 생성자로 주입받아 테스트 시 MockRestServiceServer 연결 가능
    public OAuthService(OAuthProperties oAuthProperties, AuthService authService, RestTemplate restTemplate) {
        this.oAuthProperties = oAuthProperties;
        this.authService = authService;
        this.restTemplate = restTemplate;
    }

    // ─── 카카오 OAuth 처리 ───────────────────────────────────────────────

    public Object processKakao(String authCode, String redirectUri) {
        String kakaoAccessToken = getKakaoAccessToken(authCode, redirectUri);
        KakaoUserInfo userInfo = getKakaoUserInfo(kakaoAccessToken);

        log.info("[OAuth] 카카오 로그인 시도 - providerId: {}", userInfo.getId());

        String nickname = (userInfo.getProperties() != null) ? userInfo.getProperties().getNickname() : "사용자";
        return authService.processOAuth(
            nickname,
            User.Provider.KAKAO,
            String.valueOf(userInfo.getId())
        );
    }

    private String getKakaoAccessToken(String authCode, String redirectUri) {
        String tokenUrl = "https://kauth.kakao.com/oauth/token";

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", oAuthProperties.getKakao().getClientId());
        params.add("client_secret", oAuthProperties.getKakao().getClientSecret());
        params.add("redirect_uri", redirectUri);
        params.add("code", authCode);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<KakaoTokenResponse> response = restTemplate.postForEntity(
                tokenUrl, new HttpEntity<>(params, headers), KakaoTokenResponse.class
            );

            if (response.getBody() == null || response.getBody().getAccessToken() == null) {
                throw new AuthException("INVALID_OAUTH_CODE", "카카오 토큰 발급에 실패했습니다.");
            }
            return response.getBody().getAccessToken();
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("[OAuth] 카카오 토큰 교환 실패 - redirectUri: {}, error: {}", redirectUri, e.getMessage());
            throw new AuthException("INVALID_OAUTH_CODE", "카카오 인증 실패: " + e.getMessage());
        }
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
    private static class KakaoTokenResponse {
        @JsonProperty("access_token")
        private String accessToken;
    }

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
