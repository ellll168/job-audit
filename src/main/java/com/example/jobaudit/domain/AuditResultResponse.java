package com.example.jobaudit.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计结果响应体：主记录 + 风险信号列表 + AI 一句话锐评。
 * aiVerdict 由 LLM 生成并随主记录落库（job_audit.ai_verdict），历史记录可查询。
 */
public record AuditResultResponse(
        Long auditId,
        String companyName,
        String inputText,
        String status,
        LocalDateTime createdAt,
        List<AuditSignalRecord> signals,
        String aiVerdict) {
}
