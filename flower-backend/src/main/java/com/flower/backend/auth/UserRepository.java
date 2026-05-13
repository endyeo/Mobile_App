// [기능 ID: AUTH-01~06] [명세 근거: PRD §4.0]
package com.flower.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // 소셜 로그인용: 어떤 소셜 + 소셜 고유 ID로 유저 찾기
    Optional<User> findByProviderAndProviderId(User.Provider provider, String providerId);

    // 닉네임 중복 체크
    boolean existsByNickname(String nickname);
}
