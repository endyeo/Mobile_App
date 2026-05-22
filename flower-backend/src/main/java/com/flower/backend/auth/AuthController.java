package com.flower.backend.auth;

import com.flower.backend.auth.AuthDto.*;
import com.flower.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
    }

    @PostMapping("/profile-setup")
    public ResponseEntity<ApiResponse<LoginResponse>> setupProfile(@Valid @RequestBody ProfileSetupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(authService.setupProfile(request)));
    }

    @PostMapping("/oauth/kakao")
    public ResponseEntity<ApiResponse<?>> oauthKakao(@Valid @RequestBody OAuthRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                oAuthService.processKakao(request.getAuthCode(), request.getRedirectUri())));
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
