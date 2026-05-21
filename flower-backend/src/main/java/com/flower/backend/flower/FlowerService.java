package com.flower.backend.flower;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowerService {

    private final FlowerBookRepository flowerRepository;
    private final FlowerCategoryRepository categoryRepository;
    private final FlowerSpeciesMappingRepository mappingRepository;
    private final WikiSummaryService wikiSummaryService;

    public List<FlowerDto.CategoryResponse> getCategories() {
        return categoryRepository.findAllWithFlowerCount().stream()
                .map(v -> FlowerDto.CategoryResponse.builder()
                        .id(v.getId())
                        .name(v.getName())
                        .emoji(v.getEmoji())
                        .flowerCount(v.getFlowerCount())
                        .build())
                .toList();
    }

    public List<FlowerDto.FlowerSummary> getFlowersByCategory(Long categoryId) {
        return flowerRepository.findByCategoryId(categoryId).stream()
                .map(FlowerDto.FlowerSummary::from)
                .toList();
    }

    public List<FlowerDto.FlowerSummary> getFlowersByMonth(int month) {
        return flowerRepository.findByBloomMonthOrderByBloomDay(month).stream()
                .map(FlowerDto.FlowerSummary::from)
                .toList();
    }

    public FlowerDto.FlowerDetail getFlowerDetail(Long id) {
        FlowerBook flower = flowerRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("꽃을 찾을 수 없습니다."));
        return FlowerDto.FlowerDetail.from(flower);
    }

    public FlowerDto.SearchResult search(String keyword) {
        List<FlowerDto.FlowerSummary> results = flowerRepository.searchByKeyword(keyword).stream()
                .map(FlowerDto.FlowerSummary::from)
                .toList();
        return FlowerDto.SearchResult.builder()
                .keyword(keyword)
                .flowers(results)
                .total(results.size())
                .build();
    }

    public FlowerDto.MatchResult matchByScientificName(String scientificName, double confidence) {
        // 1) 도감에 학명 정확히 일치하는 꽃이 있으면 그대로 반환
        Optional<FlowerBook> exactFlower = flowerRepository.findByScientificNameIgnoreCase(scientificName);
        if (exactFlower.isPresent()) {
            FlowerBook f = exactFlower.get();
            return buildMatchResult(f.getCategory(), f, confidence, true);
        }

        // 2) 속명(genus) 기준으로 카테고리 매핑 찾기 (자동 수집 시 카테고리 지정용)
        String genus = scientificName.split(" ")[0];
        FlowerCategory matchedCategory = mappingRepository.findByAiNameIgnoreCase(genus)
                .map(FlowerSpeciesMapping::getCategory)
                .or(() -> mappingRepository.findByGenusMatch(scientificName)
                        .map(FlowerSpeciesMapping::getCategory))
                .orElse(null);

        // 3) 위키피디아에서 자동 수집 시도 → 성공 시 flower_book에 저장하고 매칭으로 반환
        FlowerBook autoCreated = createFromWikipediaIfMissing(scientificName, matchedCategory);
        if (autoCreated != null) {
            return buildMatchResult(autoCreated.getCategory(), autoCreated, confidence, true);
        }

        // 4) 카테고리만이라도 찾았으면 부분 매칭으로 반환
        if (matchedCategory != null) {
            return buildMatchResult(matchedCategory, null, confidence, true);
        }

        // 5) 아무것도 못 찾으면 "기타"로 반환
        FlowerCategory etc = categoryRepository.findByName("기타").orElse(null);
        return FlowerDto.MatchResult.builder()
                .categoryId(etc != null ? etc.getId() : null)
                .categoryName("기타")
                .categoryEmoji("🌿")
                .confidence(confidence)
                .matched(false)
                .build();
    }

    // 위키피디아 한국어 API로 꽃 정보를 받아와 flower_book에 자동 저장
    // 트랜잭션은 read-only이므로 쓰기 작업은 propagation REQUIRES_NEW로 분리
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public FlowerBook createFromWikipediaIfMissing(String scientificName, FlowerCategory category) {
        Optional<WikiSummaryService.WikiSummary> summary = wikiSummaryService.fetch(scientificName);
        if (summary.isEmpty()) return null;

        FlowerCategory finalCategory = category != null
                ? category
                : categoryRepository.findByName("기타").orElse(null);

        FlowerBook saved = flowerRepository.save(FlowerBook.builder()
                .name(summary.get().title())
                .scientificName(scientificName)
                .description(summary.get().extract())
                .imageUrl(summary.get().thumbnailUrl())
                .category(finalCategory)
                .source("WIKIPEDIA")
                .status("AUTO")
                .build());
        log.info("위키피디아 자동 수집 완료: {} (id={})", summary.get().title(), saved.getId());
        return saved;
    }

    private FlowerDto.MatchResult buildMatchResult(FlowerCategory category, FlowerBook flower,
                                                    double confidence, boolean matched) {
        return FlowerDto.MatchResult.builder()
                .categoryId(category != null ? category.getId() : null)
                .categoryName(category != null ? category.getName() : null)
                .categoryEmoji(category != null ? category.getEmoji() : null)
                .flowerId(flower != null ? flower.getId() : null)
                .flowerName(flower != null ? flower.getName() : null)
                .confidence(confidence)
                .matched(matched)
                .build();
    }
}
