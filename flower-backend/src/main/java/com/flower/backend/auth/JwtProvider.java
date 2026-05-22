// [기능 ID: AUTH-06] [명세 근거: PRD §4.0]
package com.flower.backend.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Slf4j
@Component
public class JwtProvider {

    private final Key key;
    private final long accessTokenValidMs;
    private final long refreshTokenValidMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-seconds}") long accessSec,
            @Value("${jwt.refresh-token-validity-seconds}") long refreshSec
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenValidMs = accessSec * 1000;
        this.refreshTokenValidMs = refreshSec * 1000;
    }

    // Access Token 발급
    public String generateAccessToken(Long userId) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "access")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenValidMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Refresh Token 발급
    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("type", "refresh")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshTokenValidMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 소셜 신규 유저 임시 토큰 발급 (프로필 설정용, 유효시간 10분)
    public String generateTempToken(String provider, String providerId) {
        return Jwts.builder()
                .setSubject(providerId)
                .claim("type", "temp")
                .claim("provider", provider)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 10 * 60 * 1000))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // 토큰에서 유저 ID 추출
    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    // 토큰에서 providerId 추출 (임시 토큰 전용)
    public String getProviderId(String token) {
        return getClaims(token).getSubject();
    }

    // 토큰에서 provider 추출 (임시 토큰 전용)
    public String getProvider(String token) {
        return getClaims(token).get("provider", String.class);
    }

    // 토큰 유효성 검증
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    // 토큰 type 추출 ("access" | "refresh" | "temp")
    public String getTokenType(String token) {
        try {
            return getClaims(token).get("type", String.class);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    // Access Token 만료 시간(초) 반환
    public long getAccessTokenValidSeconds() {
        return accessTokenValidMs / 1000;
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
