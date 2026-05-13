package com.flower.backend.flower;

import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/flowers")
@RequiredArgsConstructor
public class FlowerController {

    private final FlowerService flowerService;

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<FlowerDto.CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.getCategories()));
    }

    @GetMapping("/categories/{categoryId}")
    public ResponseEntity<ApiResponse<List<FlowerDto.FlowerSummary>>> getByCategory(@PathVariable Long categoryId) {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.getFlowersByCategory(categoryId)));
    }

    @GetMapping("/monthly/{month}")
    public ResponseEntity<ApiResponse<List<FlowerDto.FlowerSummary>>> getByMonth(@PathVariable int month) {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.getFlowersByMonth(month)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FlowerDto.FlowerDetail>> getDetail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.getFlowerDetail(id)));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<FlowerDto.SearchResult>> search(@RequestParam String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.search(keyword)));
    }

    @GetMapping("/match")
    public ResponseEntity<ApiResponse<FlowerDto.MatchResult>> match(
            @RequestParam String scientificName,
            @RequestParam(defaultValue = "0.0") double confidence) {
        return ResponseEntity.ok(ApiResponse.ok(flowerService.matchByScientificName(scientificName, confidence)));
    }
}
