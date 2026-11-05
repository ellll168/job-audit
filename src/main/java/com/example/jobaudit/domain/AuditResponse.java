package com.example.jobaudit.domain;

import java.util.List;

/**
 * POST /api/audits 响应体：入库记录 id + 状态 + 信号列表（规则信号 + LLM 语义信号，均带 source 与 confidence）。
 */
public record AuditResponse(Long auditId, String status, List<AuditSignalView> signals) {
}
