package com.flower.backend.community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {

    @Query("SELECT p FROM CommunityPost p ORDER BY p.createdAt DESC")
    List<CommunityPost> findFeed(Pageable pageable);

    @Query("SELECT p FROM CommunityPost p WHERE p.id < :cursor ORDER BY p.createdAt DESC")
    List<CommunityPost> findFeedByCursor(@Param("cursor") Long cursor, Pageable pageable);
}
