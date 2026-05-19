package com.flower.backend.community;

import com.flower.backend.common.response.ApiResponse;
import com.flower.backend.plantid.PlantIdService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/flower-spots")
@RequiredArgsConstructor
public class FlowerSpotController {

    private final CommunityService communityService;
    private final PlantIdService plantIdService;

    // Plant.id 식물 인식 (사진 업로드 → 결과 반환)
    @PostMapping("/identify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> identify(
            @RequestParam("image") MultipartFile image) throws Exception {

        PlantIdService.PlantIdResult result = plantIdService.identify(image.getBytes());
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "plantName", result.plantName(),
                "confidence", result.confidence(),
                "isPlant", result.isPlant()
        )));
    }

    // 꽃 지도 게시글 작성
    @PostMapping
    public ResponseEntity<ApiResponse<CommunityDto.PostResponse>> createFlowerSpot(
            Authentication auth,
            @RequestParam("image") MultipartFile image,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "plantName") String plantName,
            @RequestParam(value = "plantConfidence", defaultValue = "0") float plantConfidence,
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @RequestParam(value = "address", required = false) String address,
            @RequestParam(value = "notifyOthers", defaultValue = "false") boolean notifyOthers) {

        Long userId = (Long) auth.getPrincipal();
        CommunityDto.PostResponse response = communityService.createFlowerSpot(
                userId, image, content, plantName, plantConfidence,
                latitude, longitude, address, notifyOthers);
        return ResponseEntity.status(201).body(ApiResponse.ok(response));
    }

    // 꽃 지도 게시글 목록 (지도 표시용 — GPS 있는 것만)
    // radius 미지정 시 위치 필터 없이 days 이내 모든 게시글 반환 (전체 지도 뷰)
    // radius 지정 시 lat/lng 기준 반경 검색 (근처 알림 등 위치 기반 필터)
    @GetMapping
    public ResponseEntity<ApiResponse<CommunityDto.FeedResponse>> getFlowerSpots(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double radius,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Long cursor) {

        return ResponseEntity.ok(ApiResponse.ok(
                communityService.getFlowerSpots(lat, lng, radius, days, cursor)));
    }
}
