package com.flower.backend.community;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

public class CommunityDto {

    @Getter
    @Builder
    public static class PostResponse {
        private Long id;
        private Long userId;
        private String nickname;
        private String profileImageUrl;
        private String content;
        private String flowerSpecies;
        private String imageUrl;
        private String address;
        private Double latitude;
        private Double longitude;
        private int likeCount;
        private boolean liked;
        private boolean saved;
        private String createdAt;
        private String postType;
        private String plantName;
        private Float plantConfidence;
    }

    @Getter
    @Builder
    public static class FeedResponse {
        private List<PostResponse> posts;
        private Long nextCursor;
        private boolean hasNext;
    }

    @Getter
    public static class CreatePostRequest {
        private String content;
        private String flowerSpecies;
        private String address;
        private Double latitude;
        private Double longitude;
    }
}
