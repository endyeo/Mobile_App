package com.flower.backend.map;

import com.flower.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
@RequiredArgsConstructor
public class TransitRouteController {

    private final TransitRouteService transitRouteService;

    @PostMapping("/transit-route")
    public ResponseEntity<ApiResponse<TransitRouteDto.TransitRouteResponse>> getTransitRoute(
            @RequestBody TransitRouteDto.TransitRouteRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(transitRouteService.getTransitRoute(request)));
    }

    @PostMapping("/routes")
    public ResponseEntity<ApiResponse<TransitRouteDto.TransitRouteResponse>> getRoute(
            @RequestBody TransitRouteDto.TransitRouteRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(transitRouteService.getTransitRoute(request)));
    }
}
