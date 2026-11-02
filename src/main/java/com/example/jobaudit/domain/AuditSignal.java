package com.example.jobaudit.domain;

/**
 * 审计信号：规则引擎命中风险后输出的结构化结果。
 */
public record AuditSignal(String signalType, String severity, String reason) {
}
