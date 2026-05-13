// [기능 ID: AUTH-02,04] [명세 근거: PRD §4.0 / application-auth.yml]
package com.flower.backend.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application-auth.yml의 oauth.* 설정값을 자바 객체로 바인딩.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "oauth")
public class OAuthProperties {

    private Kakao kakao = new Kakao();

    @Getter
    @Setter
    public static class Kakao {
        private String clientId;
        private String clientSecret;
    }
}
