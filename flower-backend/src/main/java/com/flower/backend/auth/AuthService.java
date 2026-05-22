// [기능 ID: AUTH-01~06] [명세 근거: PRD §4.0 / API Spec §2.1~2.6]
package com.flower.backend.auth;

import com.flower.backend.auth.AuthDto.*;
import com.flower.backend.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final StorageService storageService;

    // 일반 회원가입/로그인 제거됨 (소셜 전용)

    // ─── 소셜(OAuth) 로그인/회원가입 ─────────────────────────────────────
    // 실제 소셜 API 호출은 각 Provider별 OAuthService 에서 처리 후 이 메서드로 유저 정보를 전달
    @Transactional
    public Object processOAuth(String nickname, User.Provider provider, String providerId) {
        // provider + providerId로 기존 소셜 유저 찾기
        return userRepository.findByProviderAndProviderId(provider, providerId)
                .map(existingUser -> (Object) buildLoginResponse(existingUser)) // 기존 유저 → 바로 로그인
                .orElseGet(() -> {
                    // 완전 신규 유저 → 프로필 설정 화면으로 안내
                    String tempToken = jwtProvider.generateTempToken(provider.name(), providerId);
                    return OAuthNewUserResponse.builder()
                            .isNewUser(true)
                            .tempToken(tempToken)
                            .provider(provider.name())
                            .build();
                });
    }


    // ─── 소셜 신규 유저 프로필 설정 후 최종 회원가입 ─────────────────────
    @Transactional
    public LoginResponse setupProfile(ProfileSetupRequest request) {
        if (!jwtProvider.validateToken(request.getTempToken())) {
            throw new AuthException("TEMP_TOKEN_EXPIRED", "임시 토큰이 만료되었습니다.");
        }
        if (userRepository.existsByNickname(request.getNickname())) {
            throw new AuthException("NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다.");
        }

        String providerStr = jwtProvider.getProvider(request.getTempToken());
        String providerId = jwtProvider.getProviderId(request.getTempToken());
        User.Provider provider = User.Provider.valueOf(providerStr);

        // 이미 같은 provider+providerId로 가입된 유저가 있는지 최종 확인
        User user = userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> {
                    User newUser = User.createOAuthUser(
                            request.getNickname(),
                            request.getProfileImageUrl(),
                            provider,
                            providerId
                    );
                    return userRepository.save(newUser);
                });

        return buildLoginResponse(user);
    }

    // ─── Access Token 재발급 ─────────────────────────────────────────────
    public RefreshResponse refresh(RefreshRequest request) {
        if (!jwtProvider.validateToken(request.getRefreshToken())) {
            throw new AuthException("INVALID_REFRESH_TOKEN", "유효하지 않은 Refresh Token입니다.");
        }
        Long userId = jwtProvider.getUserId(request.getRefreshToken());
        String newAccessToken = jwtProvider.generateAccessToken(userId);

        return RefreshResponse.builder()
                .accessToken(newAccessToken)
                .expiresIn(jwtProvider.getAccessTokenValidSeconds())
                .build();
    }

    // ─── FCM 토큰 저장 ───────────────────────────────────────────────────
    @Transactional
    public void saveFcmToken(Long userId, String fcmToken) {
        userRepository.findById(userId).ifPresent(user -> {
            user.updateFcmToken(fcmToken);
            userRepository.save(user);
        });
    }

    // ─── 위치 업데이트 ──────────────────────────────────────────────────────
    @Transactional
    public void updateLocation(Long userId, Double latitude, Double longitude) {
        userRepository.findById(userId).ifPresent(user -> {
            user.updateLocation(latitude, longitude);
            userRepository.save(user);
        });
    }

    // ─── 닉네임 변경 ─────────────────────────────────────────────────────
    @Transactional
    public Map<String, Object> updateNickname(Long userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new AuthException("INVALID_NICKNAME", "닉네임을 입력해주세요.");
        }
        String trimmed = nickname.trim();
        if (trimmed.length() < 2 || trimmed.length() > 10) {
            throw new AuthException("INVALID_NICKNAME_LENGTH", "닉네임은 2~10자여야 합니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        user.updateNickname(trimmed);
        userRepository.save(user);
        return Map.of("nickname", trimmed);
    }

    // ─── 프로필 이미지 변경 ──────────────────────────────────────────────
    @Transactional
    public Map<String, Object> updateProfileImage(Long userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new AuthException("INVALID_IMAGE", "이미지가 비어있습니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        String url = storageService.upload(image);
        user.updateProfileImage(url);
        userRepository.save(user);
        return Map.of("profileImageUrl", url);
    }

    // ─── 로그아웃 ────────────────────────────────────────────────────────
    // 현재는 Stateless 방식: 클라이언트에서 토큰 삭제로 처리
    // 추후 필요 시 DB에 Refresh Token 블랙리스트 테이블 추가하여 서버 측 무효화 가능
    public void logout(Long userId) {
        // FCM 토큰도 함께 초기화 (로그아웃 시 알림 안 받도록)
        userRepository.findById(userId).ifPresent(user -> {
            user.updateFcmToken(null);
            userRepository.save(user);
        });
    }

    // ─── 공통: 로그인 응답 객체 생성 ──────────────────────────────────────
    private LoginResponse buildLoginResponse(User user) {
        return LoginResponse.builder()
                .accessToken(jwtProvider.generateAccessToken(user.getId()))
                .refreshToken(jwtProvider.generateRefreshToken(user.getId()))
                .expiresIn(jwtProvider.getAccessTokenValidSeconds())
                .user(UserInfo.builder()
                        .userId(user.getId())
                        .nickname(user.getNickname())
                        .profileImageUrl(user.getProfileImageUrl())
                        .build())
                .build();
    }
}
