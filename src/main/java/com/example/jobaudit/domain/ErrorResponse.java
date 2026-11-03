package com.example.jobaudit.domain;

import java.time.LocalDateTime;

/**
 * 统一错误返回体。
 */
public record ErrorResponse(int code, String message, LocalDateTime timestamp) {

    public static ErrorResponse of(int code, String message) {
        return new ErrorResponse(code, message, LocalDateTime.now());
    }
}
