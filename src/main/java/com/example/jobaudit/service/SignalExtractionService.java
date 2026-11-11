package com.example.jobaudit.service;

import com.example.jobaudit.domain.ExtractedSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * LLM 语义信号提取：识别规则引擎覆盖不到的语义级风险。
 * 输出由 json_object 响应模式 + Prompt 契约 + 服务端 parseOrDegrade 校验共同约束。
 * 解析失败一律转 pending_review，不抛异常。
 */
@Service
public class SignalExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SignalExtractionService.class);

    /**
     * 模型输出契约：{"signalType":"...","reason":"...","confidence":0.0}。
     * 当前端点（deepseek-chat）不支持 OpenAI 的 json_schema 模式，实测返回
     * "This response_format type is unavailable now"，故降级为 json_object；
     * 字段与类型约束由 SYSTEM_PROMPT 的 Output Format 段 + parseOrDegrade 服务端校验共同保证。
     */
    private static final ResponseFormat RESPONSE_FORMAT = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .build();

    private static final SystemMessage SYSTEM_PROMPT = SystemMessage.from("""
            # Role
            你是一名资深求职风控专家，长期为求职者做招聘 JD 的“反向尽调”，\
            擅长识别招聘话术背后隐藏的真实用工风险。

            # Task
            阅读用户提供的招聘 JD 全文，识别规则引擎无法覆盖的语义陷阱，仅限以下四类：
            1. pua_culture：狼性文化、PUA 式管理话术，如“公司是家”“不加班就是不奋斗”\
            “年轻人要能吃亏”“不接受质疑就别来”。
            2. overpackaged：过度包装、画大饼，只描述公司福利、期权、发展空间等空泛内容，\
            却不提具体业务方向、岗位职责或明确薪酬。
            3. broad_duty：职责极度宽泛、一人干全栈，如同时要求产品、前端、后端、运维、\
            市场、售后且无清晰边界。
            4. salary_mismatch：薪资要求错位，岗位要求极高（全栈、总监级、多领域精通）\
            但给出的薪资远低于市场合理区间。

            # Few-shot Examples
            示例 1（overpackaged）
            输入：我们提供广阔的发展空间、扁平化管理、零食下午茶，薪资面议。
            输出：{"signalType":"overpackaged","reason":"JD大量描述公司福利和期权，但未给出具体业务方向和明确薪酬","confidence":0.85}

            示例 2（salary_mismatch）
            输入：要求精通Java、前端、运维、算法，薪资4-6K。
            输出：{"signalType":"salary_mismatch","reason":"岗位要求极高，涵盖全栈及算法，但薪资水平远低于市场合理区间","confidence":0.9}

            # Constraints
            1. 只输出一条最严重、最典型的语义信号，且判定依据必须能在 JD 原文中找到对应表述。
            2. 不要提取硬规则已经覆盖的内容，出现下列情况一律忽略：
               - 薪资“面议”或薪资范围上下限差距超过 2 倍（salary_opacity）
               - “完成相关工作”“协助部门事务”等职责套话（vague_duty）
               - 同时要求“资深/专家”与“应届/小白/无经验”（contradictory_req）
               - “急招/火速/urgent”但未写明具体业务线（urgency_noise）
               - “培训费/押金/先培训后上岗/包就业”（scam_risk）
               - “抗压/能吃苦”与“单休/大小周”同时出现（overwork_risk）
               注意：硬规则只判断薪资是否透明（面议/跨度异常），不判断薪资与要求是否匹配；\
               要求极高但薪资极低，仍应提取 salary_mismatch。
            3. 不得臆测 JD 中没有的信息，不得输出歧视性或违法判断。
            4. reason 用中文，一句话说明触发依据，并引用 JD 中的关键短语（用引号包裹）。
            5. confidence 为 0~1 的小数，表示你的把握程度。
            6. 以上四类信号均未命中时，signalType 返回 "none"，reason 返回 "未发现语义陷阱"，\
            confidence 返回 0.0。

            # Output Format
            只输出一个 JSON 对象，不要 Markdown、不要代码块、不要任何解释性文字，\
            也不要输出示例中的内容。字段必须严格为以下三个，不可缺失、不可改名、不可新增：
            {"signalType": "pua_culture | overpackaged | broad_duty | salary_mismatch | none", \
            "reason": "中文说明，含原文关键短语", "confidence": 0.0}
            """);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 是否允许真实调用大模型；demo profile 置为 false，直接走降级，避免演示时长时间等待 */
    private final boolean aiEnabled;

    /** 模型未给出 confidence 时的兜底置信度 */
    private static final double DEFAULT_CONFIDENCE = 0.8;

    public SignalExtractionService(ChatModel chatModel,
                                   @Value("${jobaudit.ai.enabled:true}") boolean aiEnabled) {
        this.chatModel = chatModel;
        this.aiEnabled = aiEnabled;
    }

    public ExtractedSignal extract(String jdText) {
        if (!aiEnabled) {
            log.info("AI 外呼已关闭（demo 模式），跳过模型调用，直接转入 pending_review");
            return ExtractedSignal.pendingReview(null);
        }
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
     * 解析模型输出：JSON 非法 / signalType 或 reason 缺失，一律降级 pending_review，不抛异常。
     * 注意：Jackson 对缺失字段默认不报错（解析为 null），所以必须显式校验，否则坏数据会静默通过。
     * confidence 缺失时按默认值 0.8 兜底，不因此丢弃整条信号。
     */
    public ExtractedSignal parseOrDegrade(String raw) {
        try {
            ExtractedSignal parsed = objectMapper.readValue(raw, ExtractedSignal.class);
            if (parsed.signalType() == null || parsed.reason() == null) {
                throw new IllegalArgumentException("模型输出缺少必需字段 signalType 或 reason");
            }
            Double confidence = parsed.confidence() == null ? DEFAULT_CONFIDENCE : parsed.confidence();
            return ExtractedSignal.ok(parsed.signalType(), parsed.reason(), confidence, raw);
        } catch (Exception e) {
            log.warn("LLM 输出解析失败，转入 pending_review，原始响应={}", raw, e);
            return ExtractedSignal.pendingReview(raw);
        }
    }
}
