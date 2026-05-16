package com.flower.backend.community;

import com.flower.backend.community.CommunityDto.*;
import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @GetMapping("/posts")
    public ResponseEntity<ApiResponse<FeedResponse>> getFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                communityService.getFeed(getUserId(), cursor, limit)));
    }

    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<PostResponse>> createPost(
            @RequestParam String content,
            @RequestParam(required = false) String flowerSpecies,
            @RequestParam(required = false) MultipartFile image,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) String address) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                communityService.createPost(getUserId(), content, flowerSpecies, image, latitude, longitude, address)));
    }

    @PostMapping("/posts/{postId}/like")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggleLike(@PathVariable Long postId) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.toggleLike(getUserId(), postId)));
    }

    @PostMapping("/posts/{postId}/save")
    public ResponseEntity<ApiResponse<Map<String, Object>>> toggleSave(@PathVariable Long postId) {
        return ResponseEntity.ok(ApiResponse.ok(communityService.toggleSave(getUserId(), postId)));
    }

    @GetMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<List<CommunityDto.CommentResponse>>> getComments(
            @PathVariable Long postId) {
        Long userId = getUserIdOrNull();
        return ResponseEntity.ok(ApiResponse.ok(communityService.getComments(postId, userId)));
    }

    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<ApiResponse<CommunityDto.CommentResponse>> addComment(
            @PathVariable Long postId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                communityService.addComment(getUserId(), postId, body.get("content"))));
    }

    @DeleteMapping("/posts/{postId}/comments/{commentId}")
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @PathVariable Long postId,
            @PathVariable Long commentId) {
        communityService.deleteComment(getUserId(), postId, commentId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    private Long getUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private Long getUserIdOrNull() {
        try { return getUserId(); } catch (Exception e) { return null; }
    }
}
