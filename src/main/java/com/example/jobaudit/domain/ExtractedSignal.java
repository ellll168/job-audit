package com.example.jobaudit.domain;

/**
 * LLM 语义信号提取结果。
 * status="OK" 表示模型输出合法；status="pending_review" 表示解析失败，转入人工复核。
 */
public record ExtractedSignal(String signalType, String reason, Double confidence,
                              String status, String rawResponse) {

    public static ExtractedSignal ok(String signalType, String reason, Double confidence, String rawResponse) {
        return new ExtractedSignal(signalType, reason, confidence, "OK", rawResponse);
    }

    public static ExtractedSignal pendingReview(String rawResponse) {
        return new ExtractedSignal(null, null, null, "pending_review", rawResponse);
    }
}
