package com.flower.backend.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface PostLikeRepository extends JpaRepository<PostLike, PostLikeId> {

    @Query("SELECT l.id.postId FROM PostLike l WHERE l.id.userId = :userId AND l.id.postId IN :postIds")
    Set<Long> findLikedPostIds(@Param("userId") Long userId, @Param("postIds") Set<Long> postIds);
}
