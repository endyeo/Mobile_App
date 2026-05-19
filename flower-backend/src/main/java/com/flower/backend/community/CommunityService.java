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

import java.time.LocalDateTime;
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
    private final CommentRepository commentRepository;

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

    @Transactional
    public PostResponse createFlowerSpot(Long userId, MultipartFile image, String content,
                                          String plantName, float plantConfidence,
                                          Double latitude, Double longitude, String address,
                                          boolean notifyOthers) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        String imageUrl = null;
        if (image != null && !image.isEmpty()) {
            imageUrl = storageService.upload(image);
        }

        CommunityPost post = CommunityPost.builder()
                .user(user).content(content).imageUrl(imageUrl)
                .latitude(latitude).longitude(longitude).address(address)
                .postType("FLOWER_SPOT").plantName(plantName)
                .plantConfidence(plantConfidence).notifyOthers(notifyOthers)
                .build();

        return toResponse(postRepository.save(post), Set.of(), Set.of());
    }

    @Transactional(readOnly = true)
    public FeedResponse getFlowerSpots(Double lat, Double lng, Double radius, int days, Long cursor) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        int limit = 21;

        // 위치 + 반경 모두 지정된 경우만 PostGIS 반경 검색
        // 미지정 시 days 이내 모든 FLOWER_SPOT 반환 (지도 전체 뷰 등)
        List<CommunityPost> posts;
        if (lat != null && lng != null && radius != null) {
            posts = cursor == null
                    ? postRepository.findFlowerSpotsNearby(lat, lng, radius, since, limit)
                    : postRepository.findFlowerSpotsNearbyByCursor(cursor, lat, lng, radius, since, limit);
        } else {
            var pageable = PageRequest.of(0, limit);
            posts = cursor == null
                    ? postRepository.findFlowerSpots(since, pageable)
                    : postRepository.findFlowerSpotsByCursor(cursor, since, pageable);
        }

        boolean hasNext = posts.size() > 20;
        if (hasNext) posts = posts.subList(0, 20);
        Long nextCursor = hasNext ? posts.get(posts.size() - 1).getId() : null;

        return FeedResponse.builder()
                .posts(posts.stream().map(p -> toResponse(p, Set.of(), Set.of())).collect(Collectors.toList()))
                .nextCursor(nextCursor).hasNext(hasNext).build();
    }

    // ── 댓글 ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CommunityDto.CommentResponse> getComments(Long postId, Long currentUserId) {
        return commentRepository.findByPostId(postId).stream()
                .map(c -> CommunityDto.CommentResponse.builder()
                        .id(c.getId())
                        .userId(c.getUser().getId())
                        .nickname(c.getUser().getNickname())
                        .profileImageUrl(c.getUser().getProfileImageUrl())
                        .content(c.getContent())
                        .createdAt(c.getCreatedAt() != null ? c.getCreatedAt().toString() : "")
                        .mine(currentUserId != null && currentUserId.equals(c.getUser().getId()))
                        .build())
                .toList();
    }

    @Transactional
    public CommunityDto.CommentResponse addComment(Long userId, Long postId, String content) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new RuntimeException("게시글을 찾을 수 없습니다."));

        Comment comment = Comment.builder().post(post).user(user).content(content).build();
        commentRepository.save(comment);
        post.increaseCommentCount();
        postRepository.save(post);

        return CommunityDto.CommentResponse.builder()
                .id(comment.getId())
                .userId(user.getId())
                .nickname(user.getNickname())
                .profileImageUrl(user.getProfileImageUrl())
                .content(content)
                .createdAt("")
                .mine(true)
                .build();
    }

    @Transactional
    public void deleteComment(Long userId, Long postId, Long commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("댓글을 찾을 수 없습니다."));
        if (!comment.getUser().getId().equals(userId)) {
            throw new RuntimeException("본인 댓글만 삭제할 수 있습니다.");
        }
        commentRepository.delete(comment);
        postRepository.findById(postId).ifPresent(p -> {
            p.decreaseCommentCount();
            postRepository.save(p);
        });
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
                .postType(post.getPostType())
                .plantName(post.getPlantName())
                .plantConfidence(post.getPlantConfidence())
                .commentCount(post.getCommentCount())
                .build();
    }
}
