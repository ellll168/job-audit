package com.example.jobaudit.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计结果响应体：主记录 + 风险信号列表 + AI 一句话锐评 + 避坑建议 + 零信号中性提示。
 * aiVerdict 由 LLM 生成并随主记录落库（job_audit.ai_verdict），历史记录可查询；
 * avoidanceTips 为按需生成的轻量建议，只在内存缓存复用，不落库；
 * neutralVerdict 仅在未命中任何风险信号时给出，避免"0 分 = 绝对安全"的误读。
 */
public record AuditResultResponse(
        Long auditId,
        String companyName,
        String inputText,
        String status,
        LocalDateTime createdAt,
        List<AuditSignalRecord> signals,
        String aiVerdict,
        List<String> avoidanceTips,
        String neutralVerdict) {
}
