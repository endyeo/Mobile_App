package com.flower.backend.flower;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

public class FlowerDto {

    @Getter
    @Builder
    public static class CategoryResponse {
        private Long id;
        private String name;
        private String emoji;
        private long flowerCount;

        public static CategoryResponse from(FlowerCategory c, long count) {
            return CategoryResponse.builder()
                    .id(c.getId())
                    .name(c.getName())
                    .emoji(c.getEmoji())
                    .flowerCount(count)
                    .build();
        }
    }

    @Getter
    @Builder
    public static class FlowerSummary {
        private Long id;
        private String name;
        private String categoryName;
        private String categoryEmoji;
        private Integer bloomMonth;
        private Integer bloomDay;
        private String flowerLanguage;
        private String imageUrl;

        public static FlowerSummary from(FlowerBook f) {
            return FlowerSummary.builder()
                    .id(f.getId())
                    .name(f.getName())
                    .categoryName(f.getCategory() != null ? f.getCategory().getName() : null)
                    .categoryEmoji(f.getCategory() != null ? f.getCategory().getEmoji() : null)
                    .bloomMonth(f.getBloomMonth())
                    .bloomDay(f.getBloomDay())
                    .flowerLanguage(f.getFlowerLanguage())
                    .imageUrl(f.getImageUrl())
                    .build();
        }
    }

    @Getter
    @Builder
    public static class FlowerDetail {
        private Long id;
        private String name;
        private String scientificName;
        private String categoryName;
        private String categoryEmoji;
        private Integer bloomMonth;
        private Integer bloomDay;
        private String flowerLanguage;
        private String description;
        private String growTips;
        private String imageUrl;

        public static FlowerDetail from(FlowerBook f) {
            return FlowerDetail.builder()
                    .id(f.getId())
                    .name(f.getName())
                    .scientificName(f.getScientificName())
                    .categoryName(f.getCategory() != null ? f.getCategory().getName() : null)
                    .categoryEmoji(f.getCategory() != null ? f.getCategory().getEmoji() : null)
                    .bloomMonth(f.getBloomMonth())
                    .bloomDay(f.getBloomDay())
                    .flowerLanguage(f.getFlowerLanguage())
                    .description(f.getDescription())
                    .growTips(f.getGrowTips())
                    .imageUrl(f.getImageUrl())
                    .build();
        }
    }

    @Getter
    @Builder
    public static class SearchResult {
        private List<FlowerSummary> flowers;
        private String keyword;
        private int total;
    }

    // Plant.id 매칭 결과
    @Getter
    @Builder
    public static class MatchResult {
        private Long categoryId;
        private String categoryName;
        private String categoryEmoji;
        private Long flowerId;
        private String flowerName;
        private double confidence;
        private boolean matched;
    }
}
