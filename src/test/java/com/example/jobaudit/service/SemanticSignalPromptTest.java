package com.example.jobaudit.service;

import com.example.jobaudit.domain.ExtractedSignal;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义 Prompt 契约测试：ChatModel 用 Mockito 隔离，不依赖真实 DeepSeek Key 与网络，
 * 离线执行 mvn test 也能稳定通过。
 * 覆盖三件事：1) Prompt 结构（角色 / Few-shot / 硬规则边界 / 输出格式）
 * 2) 合法 JSON 能被解析成信号 3) 坏 JSON 降级 pending_review。
 * <p>
 * 需要看真实模型效果时改用：mvn test -Dtest=ChatModelSmokeTest -Dllm.live=true
 */
@SpringBootTest(properties = {
        "LLM_API_KEY=test-key",
        "langchain4j.open-ai.chat-model.api-key=test-key"})
@ActiveProfiles("test")
class SemanticSignalPromptTest {

    @Autowired
    SignalExtractionService service;

    @MockitoBean
    ChatModel chatModel;

    @Test
    void promptShouldContainFewShotAndHardRuleBoundary() {
        stubModel("{\"signalType\":\"none\",\"reason\":\"未发现语义陷阱\",\"confidence\":0.0}");

        service.extract("招聘 Java 后端工程师，负责订单服务开发，薪资 20-30K。");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        ChatRequest request = captor.getValue();

        String systemPrompt = ((SystemMessage) request.messages().get(0)).text();
        assertTrue(systemPrompt.contains("# Few-shot Examples"), "Prompt 缺少 Few-shot 段");
        assertTrue(systemPrompt.contains("salary_opacity"), "Prompt 缺少硬规则边界（避免重复提取）");
        assertTrue(systemPrompt.contains("# Output Format"), "Prompt 缺少输出格式约束");
        assertEquals(ResponseFormatType.JSON, request.responseFormat().type(), "应启用 json_object 响应模式");
    }

    @Test
    void validJsonShouldBeParsedIntoSignal() {
        stubModel("{\"signalType\":\"broad_duty\",\"reason\":\"JD 要求一人包揽前后端、运维与售后\",\"confidence\":0.88}");

        ExtractedSignal signal = service.extract("独立负责产品、前端、后端、运维、售后全流程。");

        assertEquals("OK", signal.status());
        assertEquals("broad_duty", signal.signalType());
        assertEquals(0.88, signal.confidence(), 0.0);
    }

    @Test
    void brokenJsonShouldDegradeToPendingReview() {
        stubModel("这不是 JSON");

        ExtractedSignal signal = service.extract("任意 JD 文本");

        assertEquals("pending_review", signal.status());
    }

    private void stubModel(String rawText) {
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(rawText))
                        .build());
    }
}
