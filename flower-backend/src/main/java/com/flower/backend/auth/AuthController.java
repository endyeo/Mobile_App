package com.flower.backend.auth;

import com.flower.backend.auth.AuthDto.*;
import com.flower.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final OAuthService oAuthService;

    /** dev-login 활성 여부 — 운영에서는 false (환경변수 DEV_LOGIN_ENABLED 미설정 시 기본 false) */
    @Value("${app.dev-login.enabled:${dev-login.enabled:false}}")
    private boolean devLoginEnabled;

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/profile-setup")
    public ResponseEntity<ApiResponse<LoginResponse>> setupProfile(@Valid @RequestBody ProfileSetupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(authService.setupProfile(request)));
    }

    /**
     * 개발자용 즉시 로그인.
     * 폰별 고유 devId를 받아 그 ID로 사용자 식별(provider=DEV).
     * 같은 폰 = 같은 계정 (영구), 다른 폰 = 자동으로 새 계정 생성.
     * 운영에서는 환경변수 DEV_LOGIN_ENABLED 미설정 → 403.
     */
    @PostMapping("/dev-login")
    public ResponseEntity<ApiResponse<?>> devLogin(@RequestBody Map<String, String> body) {
        if (!devLoginEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.fail("DEV_LOGIN_DISABLED", "개발자 로그인이 비활성화되어 있습니다."));
        }
        String devId = body.get("devId");
        if (devId == null || devId.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.fail("INVALID_REQUEST", "devId가 필요합니다."));
        }
        // providerId = devId 그대로 사용 (앱이 UUID 생성해서 보내옴)
        // processOAuth가 (DEV, devId)로 사용자 검색 → 없으면 생성, 있으면 그 사용자 + JWT
        Object result = authService.processOAuth(
                "개발자_" + devId.substring(0, Math.min(8, devId.length())),
                User.Provider.DEV,
                devId);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * 카카오 SDK가 받은 access token으로 로그인.
     * 카카오톡 SSO 흐름은 SDK가 안정적으로 처리하므로 권장 경로.
     */
    @PostMapping("/oauth/kakao/token")
    public ResponseEntity<ApiResponse<?>> oauthKakaoToken(@RequestBody java.util.Map<String, String> body) {
        String accessToken = body.get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.fail("INVALID_REQUEST", "accessToken이 필요합니다."));
        }
        return ResponseEntity.ok(ApiResponse.ok(
                oAuthService.processKakaoAccessToken(accessToken)));
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<ApiResponse<Void>> saveFcmToken(@RequestBody java.util.Map<String, String> body) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        authService.saveFcmToken(userId, body.get("fcmToken"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/location")
    public ResponseEntity<ApiResponse<Void>> updateLocation(@RequestBody java.util.Map<String, Double> body) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        authService.updateLocation(userId, body.get("latitude"), body.get("longitude"));
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PatchMapping("/profile/nickname")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateNickname(
            @RequestBody Map<String, String> body) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(ApiResponse.ok(
                authService.updateNickname(userId, body.get("nickname"))));
    }

    @PostMapping(value = "/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfileImage(
            @RequestParam("image") MultipartFile image) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(ApiResponse.ok(
                authService.updateProfileImage(userId, image)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        authService.logout(userId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
