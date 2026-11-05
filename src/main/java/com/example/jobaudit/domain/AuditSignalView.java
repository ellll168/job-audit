package com.example.jobaudit.domain;

import java.math.BigDecimal;

/**
 * POST /api/audits 返回的信号视图：统一携带来源与置信度。
 * source 区分来源（RULE = 规则引擎，LLM = 大模型）；规则信号没有置信度，confidence 为 null。
 */
public record AuditSignalView(String signalType, String severity, String reason,
                              String source, BigDecimal confidence) {

    public static AuditSignalView fromRule(AuditSignal signal) {
        return new AuditSignalView(signal.signalType(), signal.severity(), signal.reason(), "RULE", null);
    }

    public static AuditSignalView fromLlm(ExtractedSignal signal, String severity) {
        return new AuditSignalView(signal.signalType(), severity, signal.reason(),
                "LLM", BigDecimal.valueOf(signal.confidence()));
    }
}
