package com.flower.backend.walk;

import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/walk")
@RequiredArgsConstructor
public class WalkController {

    private final WalkService walkService;

    @GetMapping("/records/weekly")
    public ResponseEntity<ApiResponse<List<WalkDto.DailyRecord>>> getWeekly() {
        return ResponseEntity.ok(ApiResponse.ok(walkService.getWeeklyRecords(getUserId())));
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<WalkDto.SyncResponse>> sync(
            @RequestBody WalkDto.SyncRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                walkService.syncTodaySteps(getUserId(), request.stepCount())));
    }

    private Long getUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
