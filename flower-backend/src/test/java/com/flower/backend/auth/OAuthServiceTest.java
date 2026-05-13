// [기능 ID: AUTH-02,04] [테스트 대상: OAuthService]
package com.flower.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * OAuthService 단위 테스트
 * - MockRestServiceServer: 실제 HTTP 요청 없이 가짜 카카오 서버 응답을 만들어 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuthService 단위 테스트 (Kakao)")
class OAuthServiceTest {

    private OAuthService oAuthService;
    private MockRestServiceServer mockServer;

    @Mock
    private AuthService authService;

    @Mock
    private OAuthProperties oAuthProperties;

    @Mock
    private OAuthProperties.Kakao kakaoProps;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        oAuthService = new OAuthService(oAuthProperties, authService, restTemplate);

        given(oAuthProperties.getKakao()).willReturn(kakaoProps);
        given(kakaoProps.getClientId()).willReturn("test-kakao-client-id");
        given(kakaoProps.getClientSecret()).willReturn("test-kakao-secret");
    }

    @Test
    @DisplayName("카카오 OAuth - 기존 회원이면 로그인 토큰 즉시 반환")
    void kakao_existingUser_returnsLoginResponse() {
        mockServer.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"access_token\":\"kakao-access-token-abc\"}",
                MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "{\"id\":123456,\"properties\":{\"nickname\":\"꽃사랑\"}}",
                MediaType.APPLICATION_JSON));

        var loginResponse = AuthDto.LoginResponse.builder()
            .accessToken("our-access-token")
            .refreshToken("our-refresh-token")
            .expiresIn(3600L)
            .user(AuthDto.UserInfo.builder().userId(1L).nickname("꽃사랑").build())
            .build();
        given(authService.processOAuth("꽃사랑", User.Provider.KAKAO, "123456"))
            .willReturn(loginResponse);

        Object result = oAuthService.processKakao("test-auth-code", "http://localhost/redirect");

        assertThat(result).isInstanceOf(AuthDto.LoginResponse.class);
        AuthDto.LoginResponse response = (AuthDto.LoginResponse) result;
        assertThat(response.getAccessToken()).isEqualTo("our-access-token");
        assertThat(response.getUser().getNickname()).isEqualTo("꽃사랑");
        mockServer.verify();
    }

    @Test
    @DisplayName("카카오 OAuth - 신규 회원이면 프로필 설정 안내(tempToken 반환)")
    void kakao_newUser_returnsTempToken() {
        mockServer.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"access_token\":\"kakao-access-token-new\"}",
                MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "{\"id\":999999,\"properties\":{\"nickname\":\"신입\"}}",
                MediaType.APPLICATION_JSON));

        var newUserResponse = AuthDto.OAuthNewUserResponse.builder()
            .isNewUser(true)
            .tempToken("temp-kakao-token")
            .provider("KAKAO")
            .build();
        given(authService.processOAuth("신입", User.Provider.KAKAO, "999999"))
            .willReturn(newUserResponse);

        Object result = oAuthService.processKakao("test-auth-code", "http://localhost/redirect");

        assertThat(result).isInstanceOf(AuthDto.OAuthNewUserResponse.class);
        AuthDto.OAuthNewUserResponse response = (AuthDto.OAuthNewUserResponse) result;
        assertThat(response.isNewUser()).isTrue();
        assertThat(response.getTempToken()).isEqualTo("temp-kakao-token");
        assertThat(response.getProvider()).isEqualTo("KAKAO");
        mockServer.verify();
    }

    @Test
    @DisplayName("카카오 OAuth - 토큰 발급 실패 시 INVALID_OAUTH_CODE 예외")
    void kakao_invalidAuthCode_throwsException() {
        mockServer.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
            oAuthService.processKakao("bad-code", "http://localhost/redirect"))
            .isInstanceOf(AuthException.class)
            .hasFieldOrPropertyWithValue("errorCode", "INVALID_OAUTH_CODE");

        mockServer.verify();
    }
}
