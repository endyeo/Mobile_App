package com.flower.backend.community.repository;

import com.flower.backend.community.entity.Post;

import java.util.List;

public interface PostRepository {
    List<Post> findAll();
    List<Post> searchByKeyword(String keyword);
}
