package com.example.jobaudit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.jobaudit.domain.AuditResultResponse;
import com.example.jobaudit.domain.AuditSignal;
import com.example.jobaudit.domain.AuditSignalRecord;
import com.example.jobaudit.domain.ExtractedSignal;
import com.example.jobaudit.domain.InterviewQuestionsResponse;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.exception.AuditNotFoundException;
import com.example.jobaudit.mapper.AuditSignalRecordMapper;
import com.example.jobaudit.mapper.JobAuditMapper;
import com.example.jobaudit.rule.AuditRuleEngine;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 审计业务编排：规则引擎 + LLM 语义提取 → 合并去重 → 落库 → AI 锐评 → 返回结果。
 * 规则计算与 LLM 调用都在事务之外完成，只有数据库写入进入短事务，
 * 避免数秒级的模型调用长时间占用数据库连接与事务。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final String SOURCE_RULE = "RULE";
    private static final String SOURCE_LLM = "LLM";
    private static final String SOURCE_SYSTEM = "SYSTEM";
    private static final String NO_SIGNAL = "none";

    /** SignalExtractionService 解析失败时的降级状态 */
    private static final String STATUS_PENDING_REVIEW = "pending_review";

    /** AI 降级标记信号：落库并透出给前端，避免"AI 挂了"被误解成"岗位无风险" */
    private static final String SIGNAL_AI_DOWN = "AI_SERVICE_DOWN";
    private static final String SEVERITY_INFO = "INFO";
    private static final String AI_DOWN_REASON = "AI 服务暂时不可用，本次仅展示规则命中结果，语义风险可能漏检";

    /** 模型未给 confidence 时的兜底值 */
    private static final double DEFAULT_LLM_CONFIDENCE = 0.8;

    /** 毒舌锐评的长度硬上限，超出则截断 */
    private static final int VERDICT_MAX_LENGTH = 30;

    /** 反问话术固定生成条数 */
    private static final int QUESTION_COUNT = 3;

    /** 避坑建议最多保留条数 */
    private static final int TIP_MAX_COUNT = 3;

    /** 单条避坑建议的字数上限 */
    private static final int TIP_MAX_LENGTH = 30;

    private static final SystemMessage VERDICT_SYSTEM_PROMPT = SystemMessage.from("""
            你是一个嘴上不饶人但句句在理的求职风控毒舌评论员。
            任务：根据招聘 JD 原文与检测到的风险信号，用一句话吐槽这个岗位最大的致命伤，不超过 30 字。
            要求：
            1. 语气犀利、幽默、带讽刺，但必须基于已检测到的风险，不得编造；
            2. 只做“吐槽定性”，绝对不要给建议、不要给问句、不要列举多条；
            3. 示例：“要求全栈大牛却只给 4-6K，还大谈狼性文化”“薪资面议单休，这哪是招人是找牛马”。
            只输出这一句话本身，不要引号、不要解释、不要换行。""");

    private static final SystemMessage QUESTION_SYSTEM_PROMPT = SystemMessage.from("""
            你是一个正在面试现场、准备向 HR 提问的求职者。
            任务：结合招聘 JD 原文与检测到的风险点，写出 3 个你当场想问 HR 的问题。
            要求：
            1. 每一句都必须是疑问句，以“？”结尾，口吻是应聘者直接向 HR 提问，不是第三人称分析；
            2. 直击痛点、语气礼貌但不退让，尽量引用 JD 细节（薪资数字、技能要求、工作制度）；
            3. 只输出问题，绝对不要写建议、不要写陈述句、不要写吐槽；
            4. 示例：“这个岗位的底薪具体是多少？综合薪资的构成能详细说下吗？”
            只输出 3 行，每行一个问题，不要编号、不要解释、不要任何多余文字。""");

    private static final SystemMessage AVOIDANCE_SYSTEM_PROMPT = SystemMessage.from("""
            你是一位说话极其直接、一针见血的资深职场导师。
            任务：根据招聘 JD 原文与检测到的风险点，给求职者 2-3 条“心里要防什么”的防御建议。
            要求：
            1. 每条不超过 30 字，写成陈述句（判断或提醒），例如“底薪不写清楚，综合月薪就是画大饼”、
               “单休还没补贴，本质是白嫖你的周末”、“职责写得越模糊，进去后杂活越多”；
            2. 必须是结论式的提醒，绝对不要写成问句，也不要写成“去问 HR 什么”的提问清单；
            3. 不要复述吐槽、不要写感慨，每条只讲一个要防的点；
            4. 只输出 2-3 行，每行一条，不要编号、不要解释、不要任何多余文字。""");

    private final AuditPersistenceService auditPersistenceService;
    private final AuditRuleEngine ruleEngine;
    private final SignalExtractionService signalExtractionService;
    private final JdSignalCache jdSignalCache;
    private final ChatModel chatModel;
    private final JobAuditMapper jobAuditMapper;
    private final AuditSignalRecordMapper signalRecordMapper;

    /** 是否允许真实调用大模型；demo profile 置为 false，锐评与反问话术直接降级，不产生任何外呼 */
    private final boolean aiEnabled;

    public AuditService(AuditPersistenceService auditPersistenceService,
                        AuditRuleEngine ruleEngine,
                        SignalExtractionService signalExtractionService,
                        JdSignalCache jdSignalCache,
                        ChatModel chatModel,
                        JobAuditMapper jobAuditMapper,
                        AuditSignalRecordMapper signalRecordMapper,
                        @Value("${jobaudit.ai.enabled:true}") boolean aiEnabled) {
        this.auditPersistenceService = auditPersistenceService;
        this.ruleEngine = ruleEngine;
        this.signalExtractionService = signalExtractionService;
        this.jdSignalCache = jdSignalCache;
        this.chatModel = chatModel;
        this.jobAuditMapper = jobAuditMapper;
        this.signalRecordMapper = signalRecordMapper;
        this.aiEnabled = aiEnabled;
    }

    /**
     * 处理一次岗位审计。
     * 事务外：规则扫描、LLM 语义提取、合并去重、AI 锐评（纯计算/调用，不持有数据库连接）；
     * 事务内：job_audit 插入 + audit_signal 批量写入，任一失败整体回滚。
     * 同一段 JD 在 24 小时内命中缓存时，信号与锐评一起复用，不再调用任何模型；
     * 仅当上次锐评生成失败（缓存里为 null）时才补生成一次。
     * AI 语义提取降级（pending_review）时，额外写入一条 SYSTEM 来源的 AI_SERVICE_DOWN 信号，
     * 让前端明确看到"AI 不可用"，而不是把"零信号"误读成"岗位安全"。
     * 降级结果不写缓存：AI 恢复后，同一段 JD 的下一次请求应重新走语义提取。
     */
    public AuditResultResponse processAndSaveAudit(String companyName, String jdText) {
        JdSignalCache.CachedAudit cached = jdSignalCache.get(jdText).orElse(null);

        List<AuditSignalRecord> merged;
        String aiVerdict;
        List<String> avoidanceTips;
        if (cached != null) {
            merged = new ArrayList<>(cached.signals());
            aiVerdict = cached.aiVerdict();
            avoidanceTips = cached.avoidanceTips();
            if (aiVerdict == null) {
                // 上次锐评生成失败（缓存里为 null）：补生成一次并刷新缓存
                aiVerdict = generateVerdict(jdText, merged);
                jdSignalCache.put(jdText, cached.signals(), aiVerdict, avoidanceTips);
            }
        } else {
            ExtractedSignals extracted = extractSignals(jdText);
            merged = new ArrayList<>(extracted.signals());
            // 锐评只看真实风险信号，不把系统降级提示喂给模型
            aiVerdict = generateVerdict(jdText, merged);
            avoidanceTips = generateAvoidanceTips(jdText, merged);
            if (extracted.aiDegraded()) {
                merged.add(aiDownRecord());
            } else {
                jdSignalCache.put(jdText, extracted.signals(), aiVerdict, avoidanceTips);
            }
        }

        JobAudit jobAudit = auditPersistenceService.saveAudit(companyName, jdText, merged, aiVerdict);

        log.info("审计完成，auditId={}，合并去重后信号数={}，已批量落库，锐评={}，避坑建议={} 条",
                jobAudit.getId(), merged.size(), aiVerdict, avoidanceTips.size());

        return new AuditResultResponse(jobAudit.getId(), companyName, jdText,
                jobAudit.getStatus(), jobAudit.getCreatedAt(), merged, aiVerdict, avoidanceTips);
    }

    /**
     * GET 场景获取避坑建议：同一段 JD 在缓存有效期内直接复用，不再调用模型；
     * 未命中时按需生成，但不回写缓存——此处信号来自数据库，可能含 SYSTEM 降级提示。
     */
    public List<String> resolveAvoidanceTips(String jdText, List<AuditSignalRecord> signals) {
        List<String> cachedTips = jdSignalCache.get(jdText)
                .map(JdSignalCache.CachedAudit::avoidanceTips)
                .orElse(null);
        if (cachedTips != null) {
            return cachedTips;
        }
        return generateAvoidanceTips(jdText, withoutSystemSignals(signals));
    }

    /** 缓存未命中时的提取路径：规则引擎 + LLM，合并去重（此时尚未生成锐评，不写缓存） */
    private ExtractedSignals extractSignals(String jdText) {
        List<AuditSignalRecord> ruleRecords = ruleEngine.analyze(jdText).stream()
                .map(this::toRuleRecord)
                .toList();
        ExtractedSignal llmSignal = signalExtractionService.extract(jdText);
        List<AuditSignalRecord> llmRecords = toLlmRecords(llmSignal);
        List<AuditSignalRecord> merged = mergeAndDeduplicate(ruleRecords, llmRecords);
        boolean aiDegraded = STATUS_PENDING_REVIEW.equals(llmSignal.status());
        log.info("JD 信号提取完成，规则信号={}，LLM信号={}，合并去重后={}，AI降级={}",
                ruleRecords.size(), llmRecords.size(), merged.size(), aiDegraded);
        return new ExtractedSignals(merged, aiDegraded);
    }

    /**
     * AI 降级标记信号：作为一条特殊记录落库，前端以"系统提示"风格展示。
     * severity 用 INFO，避免把"AI 不可用"计入风险分导致分数虚高。
     */
    private AuditSignalRecord aiDownRecord() {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(SIGNAL_AI_DOWN);
        record.setSeverity(SEVERITY_INFO);
        record.setReason(AI_DOWN_REASON);
        record.setSource(SOURCE_SYSTEM);
        return record;
    }

    /** 信号提取结果 + 是否发生 AI 降级 */
    private record ExtractedSignals(List<AuditSignalRecord> signals, boolean aiDegraded) {
    }

    /**
     * 基于已落库的风险信号生成面试反问话术（轻量 RAG：JD 原文 + 信号作为上下文注入 Prompt）。
     * 生成结果回写 job_audit.interview_questions 缓存，重复请求直接读库，不再调用模型。
     */
    public InterviewQuestionsResponse generateInterviewQuestions(Long auditId) {
        JobAudit jobAudit = jobAuditMapper.selectById(auditId);
        if (jobAudit == null) {
            throw new AuditNotFoundException(auditId);
        }
        if (jobAudit.getInterviewQuestions() != null && !jobAudit.getInterviewQuestions().isBlank()) {
            log.info("面试反问话术命中缓存，auditId={}", auditId);
            return new InterviewQuestionsResponse(
                    jobAudit.getInterviewQuestions().lines().filter(line -> !line.isBlank()).toList());
        }

        List<AuditSignalRecord> signals = withoutSystemSignals(signalRecordMapper.selectList(
                new QueryWrapper<AuditSignalRecord>().eq("audit_id", auditId).orderByAsc("id")));
        log.info("生成面试反问话术，auditId={}，信号数={}", auditId, signals.size());

        if (signals.isEmpty()) {
            return new InterviewQuestionsResponse(List.of());
        }

        List<String> questions = callQuestionsModel(signals, jobAudit.getInputText());
        if (!questions.isEmpty()) {
            jobAudit.setInterviewQuestions(String.join("\n", questions));
            jobAuditMapper.updateById(jobAudit);
        }
        return new InterviewQuestionsResponse(questions);
    }

    /**
     * 一句话毒舌锐评：JD 原文 + 风险信号组装 Prompt。
     * 模型调用失败不阻断主流程，降级为 null（前端可隐藏该区块）。
     */
    private String generateVerdict(String jdText, List<AuditSignalRecord> signals) {
        if (!aiEnabled) {
            log.info("AI 外呼已关闭（demo 模式），跳过毒舌锐评生成");
            return null;
        }
        String signalSummary = signals.stream()
                .map(s -> s.getSignalType() + "（" + s.getSeverity() + "）：" + s.getReason())
                .collect(Collectors.joining("\n"));
        String userContent = "【招聘 JD】\n" + jdText + "\n\n【检测到的风险信号】\n"
                + (signalSummary.isEmpty() ? "（无明显风险信号）" : signalSummary);
        try {
            String raw = chatModel.chat(ChatRequest.builder()
                            .messages(VERDICT_SYSTEM_PROMPT, UserMessage.from(userContent))
                            .build())
                    .aiMessage().text();
            return sanitizeVerdict(raw);
        } catch (Exception e) {
            log.warn("AI 锐评生成失败，降级为 null", e);
            return null;
        }
    }

    /** 去掉模型可能输出的引号/换行，超长截断，空结果返回 null */
    private String sanitizeVerdict(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\"'“”\\n\\r]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > VERDICT_MAX_LENGTH
                ? cleaned.substring(0, VERDICT_MAX_LENGTH)
                : cleaned;
    }

    /**
     * 避坑建议：JD 原文 + 风险信号组装 Prompt，生成 2-3 条面试可执行动作。
     * 演示模式或调用失败时降级为空列表，不阻断主流程，也不落库。
     */
    private List<String> generateAvoidanceTips(String jdText, List<AuditSignalRecord> signals) {
        if (!aiEnabled) {
            log.info("AI 外呼已关闭（demo 模式），跳过避坑建议生成");
            return List.of();
        }
        if (signals.isEmpty()) {
            log.info("无风险信号，跳过避坑建议生成");
            return List.of();
        }
        String riskContext = signals.stream()
                .map(s -> "- " + s.getSignalType() + "（" + s.getSeverity() + "）：" + s.getReason())
                .collect(Collectors.joining("\n"));
        try {
            String raw = chatModel.chat(ChatRequest.builder()
                            .messages(AVOIDANCE_SYSTEM_PROMPT, UserMessage.from(
                                    "【招聘 JD 原文】\n" + jdText + "\n\n【该岗位检测到的风险点】\n" + riskContext))
                            .build())
                    .aiMessage().text();
            return parseTips(raw);
        } catch (Exception e) {
            log.warn("AI 避坑建议生成失败，降级为空列表", e);
            return List.of();
        }
    }

    /** 按行解析：剥离序号与引号、去掉句末标点，丢弃跑成问句的行（那属于反问话术），单条超长截断，最多 3 条 */
    private List<String> parseTips(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines()
                .map(String::trim)
                .map(line -> line.replaceAll("^[0-9一二三四五六七八九十]+[.、．)]\\s*", ""))
                .map(line -> line.replaceAll("^[\"'“”「」]+|[\"'“”「」]+$", ""))
                .map(line -> line.replaceAll("[。；;]+$", ""))
                .filter(line -> !line.isEmpty())
                // 与反问话术划清边界：陈述式防御建议，不允许出现疑问句
                .filter(line -> !line.endsWith("？") && !line.endsWith("?"))
                .map(line -> line.length() > TIP_MAX_LENGTH ? line.substring(0, TIP_MAX_LENGTH) : line)
                .limit(TIP_MAX_COUNT)
                .toList();
    }

    /**
     * 调用模型生成反问问题并解析：按行拆分，剥离序号前缀，统一以“？”结尾，最多取 3 条。
     * JD 原文与风险信号一起作为上下文注入，让问题能引用具体细节（如薪资数字）。
     * 模型调用失败时抛出异常，由 GlobalExceptionHandler 统一处理。
     */
    private List<String> callQuestionsModel(List<AuditSignalRecord> signals, String jdText) {
        if (!aiEnabled) {
            log.info("AI 外呼已关闭（demo 模式），跳过面试反问话术生成");
            return List.of();
        }
        String riskContext = signals.stream()
                .map(s -> "- " + s.getSignalType() + "（" + s.getSeverity() + "）：" + s.getReason())
                .collect(Collectors.joining("\n"));
        String raw = chatModel.chat(ChatRequest.builder()
                        .messages(QUESTION_SYSTEM_PROMPT, UserMessage.from(
                                "【招聘 JD 原文】\n" + jdText + "\n\n【该岗位检测到的风险点】\n" + riskContext))
                        .build())
                .aiMessage().text();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return raw.lines()
                .map(String::trim)
                .map(line -> line.replaceAll("^[0-9一二三四五六七八九十]+[.、．)]\\s*", ""))
                .filter(line -> !line.isEmpty())
                // 与避坑建议划清边界：只保留疑问句，且一行里的多个问句拆成多条
                .flatMap(AuditService::splitQuestions)
                .filter(question -> !question.isEmpty())
                .limit(QUESTION_COUNT)
                .toList();
    }

    /** 拆行：一行里有多个问句时按问号切分，只保留疑问句，句末标点统一为“？” */
    private static Stream<String> splitQuestions(String line) {
        if (!line.contains("？") && !line.contains("?")) {
            // 模型偶尔漏写问号：整行当作一个问题，补上问号
            return Stream.of(normalizeQuestion(line));
        }
        return Arrays.stream(line.split("(?<=[？?])"))
                .map(String::trim)
                .filter(sentence -> sentence.endsWith("？") || sentence.endsWith("?"))
                .map(AuditService::normalizeQuestion);
    }

    private static String normalizeQuestion(String sentence) {
        return sentence.replaceAll("[。.；;！!？?\\s]+$", "") + "？";
    }

    /** 规则引擎输出 -> audit_signal 实体；auditId 与 createdAt 由持久化层统一回填 */
    private AuditSignalRecord toRuleRecord(AuditSignal signal) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(signal.signalType());
        record.setSeverity(signal.severity());
        record.setReason(signal.reason());
        record.setSource(SOURCE_RULE);
        return record;
    }

    /**
     * LLM 输出 -> audit_signal 实体。
     * 未产生有效语义信号（none / 解析失败 pending_review）时返回空列表，不影响规则结果。
     */
    private List<AuditSignalRecord> toLlmRecords(ExtractedSignal signal) {
        if (!"OK".equals(signal.status())
                || signal.signalType() == null
                || NO_SIGNAL.equals(signal.signalType())) {
            log.info("LLM 未产生语义信号，status={}，signalType={}", signal.status(), signal.signalType());
            return List.of();
        }
        double confidence = signal.confidence() == null ? DEFAULT_LLM_CONFIDENCE : signal.confidence();
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(signal.signalType());
        record.setSeverity(severityOf(confidence));
        record.setReason(signal.reason());
        record.setConfidence(BigDecimal.valueOf(confidence));
        record.setSource(SOURCE_LLM);
        return List.of(record);
    }

    /**
     * 合并规则信号与 LLM 信号，按 signalType 去重：
     * 1) 同一 signalType 同时被 RULE 和 LLM 命中时，保留 RULE（规则判定确定，不依赖模型输出）；
     * 2) 同类信号（同为 LLM）保留 confidence 更高者；规则信号无 confidence，比较时按 0 处理。
     */
    private List<AuditSignalRecord> mergeAndDeduplicate(List<AuditSignalRecord> ruleRecords,
                                                        List<AuditSignalRecord> llmRecords) {
        return Stream.concat(ruleRecords.stream(), llmRecords.stream())
                .filter(record -> record.getSignalType() != null)
                .collect(Collectors.toMap(
                        AuditSignalRecord::getSignalType,
                        Function.identity(),
                        this::preferSignal,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    /** 冲突裁决：规则优先于 LLM；同来源时保留置信度更高的一个 */
    private AuditSignalRecord preferSignal(AuditSignalRecord existing, AuditSignalRecord candidate) {
        boolean existingIsRule = SOURCE_RULE.equals(existing.getSource());
        boolean candidateIsRule = SOURCE_RULE.equals(candidate.getSource());
        if (existingIsRule != candidateIsRule) {
            return existingIsRule ? existing : candidate;
        }
        return confidenceOf(candidate).compareTo(confidenceOf(existing)) > 0 ? candidate : existing;
    }

    private BigDecimal confidenceOf(AuditSignalRecord record) {
        return record.getConfidence() == null ? BigDecimal.ZERO : record.getConfidence();
    }

    /** 过滤掉 SYSTEM 来源的系统级提示（如 AI_SERVICE_DOWN），避免污染风险分析与模型上下文 */
    private List<AuditSignalRecord> withoutSystemSignals(List<AuditSignalRecord> signals) {
        return signals.stream()
                .filter(record -> !SOURCE_SYSTEM.equals(record.getSource()))
                .toList();
    }

    /** LLM 不给 severity，按置信度映射：>=0.8 高危，>=0.5 中危，其余低危 */
    private String severityOf(double confidence) {
        if (confidence >= 0.8) {
            return "HIGH";
        }
        if (confidence >= 0.5) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
