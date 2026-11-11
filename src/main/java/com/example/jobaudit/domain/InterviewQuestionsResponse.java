package com.example.jobaudit.domain;

import java.util.List;

/**
 * GET /api/audits/{id}/questions 响应体：基于该次审计的风险信号生成的面试反问话术。
 */
public record InterviewQuestionsResponse(List<String> questions) {
}
