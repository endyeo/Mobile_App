package com.flower.backend.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;

public interface SavedPostRepository extends JpaRepository<SavedPost, SavedPostId> {

    @Query("SELECT s.id.postId FROM SavedPost s WHERE s.id.userId = :userId AND s.id.postId IN :postIds")
    Set<Long> findSavedPostIds(@Param("userId") Long userId, @Param("postIds") Set<Long> postIds);
}
