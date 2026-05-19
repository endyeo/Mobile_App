// [기능 ID: AUTH-01~06] [명세 근거: PRD §4.0]
package com.flower.backend.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // 소셜 로그인용: 어떤 소셜 + 소셜 고유 ID로 유저 찾기
    Optional<User> findByProviderAndProviderId(User.Provider provider, String providerId);

    // 닉네임 중복 체크
    boolean existsByNickname(String nickname);

    // PostGIS 공간 인덱스 기반 반경 검색 (idx_users_last_location GIST 인덱스 사용)
    @Query(nativeQuery = true, value = """
        SELECT * FROM users
         WHERE fcm_token IS NOT NULL
           AND last_location IS NOT NULL
           AND id <> :excludeUserId
           AND ST_DWithin(
                 last_location,
                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                 :radiusM
               )
        """)
    List<User> findNearbyUsersWithFcmToken(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusM") double radiusM,
            @Param("excludeUserId") Long excludeUserId);
}
