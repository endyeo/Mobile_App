// [기능 ID: AUTH-06] [명세 근거: PRD §4.0]
package com.flower.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. 요청 헤더에서 토큰 추출 (Authorization: Bearer {token})
        String token = resolveToken(request);

        // 2. 토큰이 있고 유효한 경우 → SecurityContext에 인증 정보 등록
        if (StringUtils.hasText(token) && jwtProvider.validateToken(token)) {
            try {
                Long userId = jwtProvider.getUserId(token);

                // DB에서 유저 정보 조회
                userRepository.findById(userId).ifPresent(user -> {
                    // 유저 권한 설정 (ROLE_USER 또는 ROLE_ADMIN)
                    var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());

                    // Spring Security 인증 객체 생성
                    var authentication = new UsernamePasswordAuthenticationToken(
                            userId,   // principal (이후 컨트롤러에서 @AuthenticationPrincipal로 꺼내 쓸 수 있음)
                            null,
                            List.of(authority)
                    );

                    // SecurityContext에 등록 → 이 요청은 "인증된 유저"로 처리됨
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("JWT 인증 성공 - userId: {}", userId);
                });

            } catch (Exception e) {
                log.warn("JWT 인증 처리 중 오류: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    // Authorization 헤더에서 "Bearer " 제거 후 토큰만 추출
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
