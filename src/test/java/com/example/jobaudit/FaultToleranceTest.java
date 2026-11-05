package com.example.jobaudit;

import com.example.jobaudit.domain.ExtractedSignal;
import com.example.jobaudit.service.SignalExtractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 容错测试：验证 SignalExtractionService 在模型输出残缺时走 pending_review 降级，不抛异常。
 */
@SpringBootTest
class FaultToleranceTest {

    @Autowired
    SignalExtractionService service;

    @Test
    void shouldDegradeToPendingReviewOnBrokenJson() {
        // 残缺 JSON：缺少 reason 和 confidence（Jackson 默认不会因此报错，靠 Service 里的显式字段校验拦截）
        String brokenJson = "{\"signalType\":\"test\"}";

        // 强行调用底层反序列化入口；若兜底失效，异常会从这里抛出来导致测试失败
        ExtractedSignal result = service.parseOrDegrade(brokenJson);

        // 走到这里说明异常已被 Service 捕获，未逃逸
        System.out.println("触发兜底逻辑，原始响应已记录");

        assertEquals("pending_review", result.status());
        assertEquals(brokenJson, result.rawResponse());
        assertNull(result.signalType());
        assertNull(result.reason());
        assertNull(result.confidence());
    }

    @Test
    void shouldDefaultConfidenceWhenModelOmitsIt() {
        // 只有 confidence 缺失：signalType/reason 齐全，应正常返回并兜底 0.8，而不是丢掉整条信号
        String json = "{\"signalType\":\"pua_culture\",\"reason\":\"JD 使用狼性话术\"}";

        ExtractedSignal result = service.parseOrDegrade(json);

        assertEquals("OK", result.status());
        assertEquals("pua_culture", result.signalType());
        assertEquals(0.8, result.confidence(), 0.0);
    }
}
