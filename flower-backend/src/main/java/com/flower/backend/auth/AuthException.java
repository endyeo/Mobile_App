// [기능 ID: AUTH-01~06]
package com.flower.backend.auth;

import lombok.Getter;

@Getter
public class AuthException extends RuntimeException {
    private final String errorCode;

    public AuthException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
