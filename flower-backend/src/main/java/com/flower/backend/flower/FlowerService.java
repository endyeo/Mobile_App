package com.flower.backend.flower;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowerService {

    private final FlowerBookRepository flowerRepository;
    private final FlowerCategoryRepository categoryRepository;
    private final FlowerSpeciesMappingRepository mappingRepository;

    public List<FlowerDto.CategoryResponse> getCategories() {
        return categoryRepository.findAll().stream()
                .map(c -> FlowerDto.CategoryResponse.from(c,
                        flowerRepository.findByCategoryId(c.getId()).size()))
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
        Optional<FlowerBook> exactFlower = flowerRepository.findByScientificNameIgnoreCase(scientificName);
        if (exactFlower.isPresent()) {
            FlowerBook f = exactFlower.get();
            return buildMatchResult(f.getCategory(), f, confidence, true);
        }

        String genus = scientificName.split(" ")[0];
        Optional<FlowerSpeciesMapping> genusMapping = mappingRepository.findByAiNameIgnoreCase(genus);
        if (genusMapping.isPresent()) {
            return buildMatchResult(genusMapping.get().getCategory(), null, confidence, true);
        }

        Optional<FlowerSpeciesMapping> partialMapping = mappingRepository.findByGenusMatch(scientificName);
        if (partialMapping.isPresent()) {
            return buildMatchResult(partialMapping.get().getCategory(), null, confidence, true);
        }

        FlowerCategory etc = categoryRepository.findByName("기타").orElse(null);
        return FlowerDto.MatchResult.builder()
                .categoryId(etc != null ? etc.getId() : null)
                .categoryName("기타")
                .categoryEmoji("🌿")
                .confidence(confidence)
                .matched(false)
                .build();
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
