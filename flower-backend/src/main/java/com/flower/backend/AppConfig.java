// [기능 ID: AUTH] SpringBoot 공통 Bean 설정
package com.flower.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    // OAuthService에서 주입받아 쓰는 RestTemplate Bean
    // 테스트에서는 MockRestServiceServer로 교체 가능
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
