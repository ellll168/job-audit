package com.example.jobaudit.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/audits/{id} 响应体：主记录 + 关联的风险信号列表。
 */
public record AuditResultResponse(
        Long auditId,
        String companyName,
        String inputText,
        String status,
        LocalDateTime createdAt,
        List<AuditSignalRecord> signals) {
}
