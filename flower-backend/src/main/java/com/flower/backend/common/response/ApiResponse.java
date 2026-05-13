package com.flower.backend.common.response;

public record ApiResponse<T>(boolean success, T data, ErrorInfo error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<?> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorInfo(code, message));
    }

    public record ErrorInfo(String code, String message) {}
}
