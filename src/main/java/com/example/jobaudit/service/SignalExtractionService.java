package com.example.jobaudit.service;

import com.example.jobaudit.domain.ExtractedSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 语义信号提取：识别规则引擎覆盖不到的语义级风险。
 * 输出由 ResponseFormat + JsonSchema 强制约束，strict 由配置 langchain4j.open-ai.chat-model.strict-json-schema=true 开启。
 * 解析失败一律转 pending_review，不抛异常。
 */
@Service
public class SignalExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SignalExtractionService.class);

    /** 模型输出契约：{"signalType":"...","reason":"...","confidence":0.0}，字段与类型严格受限 */
    private static final ResponseFormat RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                    .name("audit_signal")
                    .rootElement(JsonObjectSchema.builder()
                            .addStringProperty("signalType")
                            .addStringProperty("reason")
                            .addNumberProperty("confidence")
                            .required("signalType", "reason", "confidence")
                            .additionalProperties(false)
                            .build())
                    .build())
            .build();

    private static final SystemMessage SYSTEM_PROMPT = SystemMessage.from(
            "你是招聘 JD 风险分析专家。从用户给出的招聘 JD 中提取规则引擎难以覆盖的语义级风险信号，"
                    + "例如：暗示无偿加班的话术、PUA 式企业文化、岗位定位与实际职责不符、隐晦的歧视性要求。"
                    + "signalType 用蛇形英文命名（如 implied_unpaid_overtime），reason 用中文说明依据，"
                    + "confidence 为 0~1 的置信度。若没有发现风险，signalType 返回 none。");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SignalExtractionService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public ExtractedSignal extract(String jdText) {
        String raw = null;
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(SYSTEM_PROMPT, UserMessage.from(jdText))
                    .responseFormat(RESPONSE_FORMAT)
                    .build();
            raw = chatModel.chat(request).aiMessage().text();
        } catch (Exception e) {
            // 模型超时 / 网络错误：不抛 500，转人工复核
            log.warn("LLM 调用失败，转入 pending_review", e);
            return ExtractedSignal.pendingReview(raw);
        }
        return parseOrDegrade(raw);
    }

    /**
     * 解析模型输出：JSON 非法 / 必需字段缺失 / 出现未知字段，一律降级 pending_review，不抛异常。
     * 注意：Jackson 对缺失字段默认不报错（解析为 null），所以必须显式校验，否则坏数据会静默通过。
     */
    public ExtractedSignal parseOrDegrade(String raw) {
        try {
            ExtractedSignal parsed = objectMapper.readValue(raw, ExtractedSignal.class);
            if (parsed.signalType() == null || parsed.reason() == null || parsed.confidence() == null) {
                throw new IllegalArgumentException("模型输出缺少必需字段");
            }
            return ExtractedSignal.ok(parsed.signalType(), parsed.reason(), parsed.confidence(), raw);
        } catch (Exception e) {
            log.warn("LLM 输出解析失败，转入 pending_review，原始响应={}", raw, e);
            return ExtractedSignal.pendingReview(raw);
        }
    }
}
