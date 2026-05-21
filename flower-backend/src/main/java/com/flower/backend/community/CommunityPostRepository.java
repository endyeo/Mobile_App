package com.flower.backend.community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    @Query("SELECT p FROM CommunityPost p ORDER BY p.createdAt DESC")
    List<CommunityPost> findFeed(Pageable pageable);

    @Query("SELECT p FROM CommunityPost p WHERE p.id < :cursor ORDER BY p.createdAt DESC")
    List<CommunityPost> findFeedByCursor(@Param("cursor") Long cursor, Pageable pageable);

    // 지도용: GPS가 있는 FLOWER_SPOT 게시글만 조회
    @Query("""
        SELECT p FROM CommunityPost p
        WHERE p.postType = 'FLOWER_SPOT'
          AND p.createdAt >= :since
          AND p.latitude IS NOT NULL
          AND p.longitude IS NOT NULL
        ORDER BY p.createdAt DESC
    """)
    List<CommunityPost> findFlowerSpots(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("""
        SELECT p FROM CommunityPost p
        WHERE p.postType = 'FLOWER_SPOT'
          AND p.id < :cursor
          AND p.createdAt >= :since
          AND p.latitude IS NOT NULL
          AND p.longitude IS NOT NULL
        ORDER BY p.createdAt DESC
    """)
    List<CommunityPost> findFlowerSpotsByCursor(@Param("cursor") Long cursor, @Param("since") LocalDateTime since, Pageable pageable);

    // PostGIS 반경 기반 꽃 게시글 조회 (지도용)
    @Query(nativeQuery = true, value = """
        SELECT * FROM community_posts
         WHERE post_type = 'FLOWER_SPOT'
           AND created_at >= :since
           AND location IS NOT NULL
           AND ST_DWithin(
                 location,
                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                 :radiusM
               )
         ORDER BY created_at DESC
         LIMIT :limit
        """)
    List<CommunityPost> findFlowerSpotsNearby(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusM") double radiusM,
            @Param("since") LocalDateTime since,
            @Param("limit") int limit);

    @Query(nativeQuery = true, value = """
        SELECT * FROM community_posts
         WHERE post_type = 'FLOWER_SPOT'
           AND id < :cursor
           AND created_at >= :since
           AND location IS NOT NULL
           AND ST_DWithin(
                 location,
                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                 :radiusM
               )
         ORDER BY created_at DESC
         LIMIT :limit
        """)
    List<CommunityPost> findFlowerSpotsNearbyByCursor(
            @Param("cursor") Long cursor,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusM") double radiusM,
            @Param("since") LocalDateTime since,
            @Param("limit") int limit);

    @Query("""
        SELECT p FROM CommunityPost p
        WHERE LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
           OR LOWER(p.flowerSpecies) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY p.createdAt DESC
    """)
    List<CommunityPost> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
