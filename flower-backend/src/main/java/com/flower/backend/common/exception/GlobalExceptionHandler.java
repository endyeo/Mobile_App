package com.flower.backend.common.exception;

import com.flower.backend.auth.AuthException;
import com.flower.backend.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<?>> handleAuthException(AuthException e) {
        int status = switch (e.getErrorCode()) {
            case "NICKNAME_ALREADY_EXISTS" -> 409;
            case "INVALID_CREDENTIALS", "INVALID_REFRESH_TOKEN", "TEMP_TOKEN_EXPIRED" -> 401;
            default -> 400;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500)
                .body(ApiResponse.fail("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
