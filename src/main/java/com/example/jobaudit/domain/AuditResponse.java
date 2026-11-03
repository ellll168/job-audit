package com.example.jobaudit.domain;

import java.util.List;

/**
 * POST /api/audits 响应体：入库记录 id + 状态 + 规则引擎命中的信号列表。
 */
public record AuditResponse(Long auditId, String status, List<AuditSignal> signals) {
}
