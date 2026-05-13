package com.flower.backend.flower;

import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/flowers")
@RequiredArgsConstructor
public class FlowerImportController {

    private final NongsaroImportService importService;

    @Autowired(required = false)
    private WikiImageService wikiImageService;

    @Autowired(required = false)
    private NongsaroImageService nongsaroImageService;

    @PostMapping("/import")
    public ApiResponse<NongsaroImportService.ImportResult> importFromNongsaro() {
        return ApiResponse.ok(importService.importAll());
    }

    @PostMapping("/fetch-images")
    public ApiResponse<?> fetchImages() {
        if (wikiImageService == null) return ApiResponse.ok("로컬 환경에서는 지원되지 않습니다.");
        return ApiResponse.ok(wikiImageService.fetchAndStoreImages());
    }

    @PostMapping("/compress-images")
    public ApiResponse<?> compressImages() {
        if (nongsaroImageService == null) return ApiResponse.ok("로컬 환경에서는 지원되지 않습니다.");
        return ApiResponse.ok(nongsaroImageService.compressAndStore());
    }
}
