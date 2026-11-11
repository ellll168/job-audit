package com.example.jobaudit.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/audits 请求体。
 */
public record AuditRequest(
        @NotBlank(message = "companyName 不能为空") String companyName,
        @NotBlank(message = "JD 原文不能为空")
        @Size(max = 8000, message = "JD 原文不能超过 8000 字") String inputText) {
}
