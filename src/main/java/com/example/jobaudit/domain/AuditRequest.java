package com.example.jobaudit.domain;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/audits 请求体。
 */
public record AuditRequest(
        @NotBlank(message = "companyName 不能为空") String companyName,
        @NotBlank(message = "inputText 不能为空") String inputText) {
}
