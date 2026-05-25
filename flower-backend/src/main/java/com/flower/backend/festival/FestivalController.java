package com.flower.backend.festival;

import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 캐싱된 꽃 축제 조회 API.
 *
 * - GET /api/v1/festivals               지도/홈에서 사용. 진행·예정 축제 목록.
 * - POST /api/v1/festivals/refresh      관리용 수동 갱신 트리거 (운영 환경에선 보호 필요)
 */
@RestController
@RequestMapping("/api/v1/festivals")
@RequiredArgsConstructor
public class FestivalController {

    private final FestivalRepository festivalRepository;
    private final FestivalCacheService cacheService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFestivals(
            @RequestParam(defaultValue = "60") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 300);
        List<Festival> festivals = festivalRepository.findOngoingOrUpcomingNow(PageRequest.of(0, safeLimit));
        List<Map<String, Object>> items = festivals.stream().map(FestivalController::toMap).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        data.put("source", "db_cache");
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh() {
        int affected = cacheService.refreshCache();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("affected", affected)));
    }

    /** Flutter `FestivalData.fromApi`의 키 이름에 맞춰 반환 */
    private static Map<String, Object> toMap(Festival f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contentid", f.getContentId());
        m.put("title", f.getTitle());
        m.put("addr1", f.getAddr1() == null ? "" : f.getAddr1());
        m.put("addr2", f.getAddr2() == null ? "" : f.getAddr2());
        m.put("mapx", f.getMapX() == null ? "" : String.valueOf(f.getMapX()));
        m.put("mapy", f.getMapY() == null ? "" : String.valueOf(f.getMapY()));
        m.put("firstimage", f.getFirstImage() == null ? "" : f.getFirstImage());
        m.put("firstimage2", f.getFirstImage2() == null ? "" : f.getFirstImage2());
        m.put("tel", f.getTel() == null ? "" : f.getTel());
        m.put("eventstartdate", f.getEventStartDate() == null ? "" : f.getEventStartDate());
        m.put("eventenddate", f.getEventEndDate() == null ? "" : f.getEventEndDate());
        return m;
    }
}
