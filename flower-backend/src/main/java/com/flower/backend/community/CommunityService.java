package com.flower.backend.community;

import com.flower.backend.auth.User;
import com.flower.backend.auth.UserRepository;
import com.flower.backend.community.CommunityDto.*;
import com.flower.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final SavedPostRepository savedPostRepository;
    private final UserRepository userRepository;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public FeedResponse getFeed(Long userId, Long cursor, int limit) {
        var pageable = PageRequest.of(0, limit + 1);
        List<CommunityPost> posts = cursor == null
                ? postRepository.findFeed(pageable)
                : postRepository.findFeedByCursor(cursor, pageable);

        boolean hasNext = posts.size() > limit;
        if (hasNext) posts = posts.subList(0, limit);
        Long nextCursor = hasNext ? posts.get(posts.size() - 1).getId() : null;

        // 배치 조회로 N+1 해결
        Set<Long> postIds = posts.stream().map(CommunityPost::getId).collect(Collectors.toSet());
        Set<Long> likedIds = userId != null ? likeRepository.findLikedPostIds(userId, postIds) : Set.of();
        Set<Long> savedIds = userId != null ? savedPostRepository.findSavedPostIds(userId, postIds) : Set.of();

        return FeedResponse.builder()
                .posts(posts.stream().map(p -> toResponse(p, likedIds, savedIds)).collect(Collectors.toList()))
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    @Transactional
    public PostResponse createPost(Long userId, String content, String flowerSpecies,
                                   MultipartFile image, Double latitude, Double longitude, String address) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            imageUrl = storageService.upload(image);
        }

        CommunityPost post = CommunityPost.builder()
                .user(user).content(content).flowerSpecies(flowerSpecies)
                .imageUrl(imageUrl).latitude(latitude).longitude(longitude).address(address)
                .build();

        CommunityPost saved = postRepository.save(post);
        return toResponse(saved, Set.of(), Set.of());
    }

    @Transactional
    public Map<String, Object> toggleLike(Long userId, Long postId) {
        PostLikeId likeId = new PostLikeId(userId, postId);
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("게시글을 찾을 수 없습니다."));

        boolean liked;
        if (likeRepository.existsById(likeId)) {
            likeRepository.deleteById(likeId);
            post.decreaseLikeCount();
            liked = false;
        } else {
            likeRepository.save(new PostLike(likeId));
            post.increaseLikeCount();
            liked = true;
        }
        postRepository.save(post);
        return Map.of("liked", liked, "likeCount", post.getLikeCount());
    }

    @Transactional
    public Map<String, Object> toggleSave(Long userId, Long postId) {
        SavedPostId savedId = new SavedPostId(userId, postId);
        boolean saved;
        if (savedPostRepository.existsById(savedId)) {
            savedPostRepository.deleteById(savedId);
            saved = false;
        } else {
            savedPostRepository.save(new SavedPost(savedId));
            saved = true;
        }
        return Map.of("saved", saved);
    }

    private PostResponse toResponse(CommunityPost post, Set<Long> likedIds, Set<Long> savedIds) {
        return PostResponse.builder()
                .id(post.getId())
                .userId(post.getUser().getId())
                .nickname(post.getUser().getNickname())
                .profileImageUrl(post.getUser().getProfileImageUrl())
                .content(post.getContent())
                .flowerSpecies(post.getFlowerSpecies())
                .imageUrl(post.getImageUrl())
                .address(post.getAddress())
                .latitude(post.getLatitude())
                .longitude(post.getLongitude())
                .likeCount(post.getLikeCount())
                .liked(likedIds.contains(post.getId()))
                .saved(savedIds.contains(post.getId()))
                .createdAt(post.getCreatedAt() != null ? post.getCreatedAt().toString() : "")
                .build();
    }
}
