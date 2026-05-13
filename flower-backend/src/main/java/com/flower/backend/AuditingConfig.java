// @CreatedDate 같은 JPA Auditing 어노테이션이 동작하려면 이 설정이 필요
package com.flower.backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class AuditingConfig {
}
