package com.flower.backend.community.repository;

import com.flower.backend.community.CommunityPost;
import com.flower.backend.community.CommunityPostRepository;
import com.flower.backend.community.entity.Post;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class PostRepositoryImpl implements PostRepository {

    private final CommunityPostRepository communityPostRepository;

    @Override
    public List<Post> findAll() {
        return communityPostRepository.findFeed(PageRequest.of(0, 20)).stream()
                .map(this::toPost)
                .toList();
    }

    @Override
    public List<Post> searchByKeyword(String keyword) {
        return communityPostRepository.findAll().stream()
                .filter(p -> matches(p, keyword))
                .map(this::toPost)
                .toList();
    }

    private boolean matches(CommunityPost p, String keyword) {
        String k = keyword.toLowerCase();
        return (p.getContent() != null && p.getContent().toLowerCase().contains(k))
                || (p.getFlowerSpecies() != null && p.getFlowerSpecies().toLowerCase().contains(k));
    }

    private Post toPost(CommunityPost p) {
        String nickname = p.getUser() != null ? p.getUser().getNickname() : "익명";
        return new Post(p.getId(), nickname, p.getContent(), p.getFlowerSpecies(), p.getLikeCount());
    }
}
