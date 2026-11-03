package com.example.jobaudit.rule;

import com.example.jobaudit.domain.AuditSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计规则引擎：对 JD 文本执行硬规则扫描，输出命中的审计信号。
 */
@Component
public class AuditRuleEngine {

    /** 薪资范围，如 10-20K、15k~30k */
    private static final Pattern SALARY_RANGE = Pattern.compile("(\\d+)\\s*[-~]\\s*(\\d+)\\s*[kK]");

    /** 职责模糊的典型套话 */
    private static final Pattern VAGUE_DUTY = Pattern.compile("完成相关工作|协助部门事务");

    /** 要求矛盾：资深/专家 与 应届/小白/无经验 同时出现 */
    private static final Pattern SENIOR_REQ = Pattern.compile("资深|专家");
    private static final Pattern JUNIOR_REQ = Pattern.compile("应届|小白|无经验");

    /** 紧迫话术（urgent 忽略大小写） */
    private static final Pattern URGENCY = Pattern.compile("急招|火速|urgent", Pattern.CASE_INSENSITIVE);

    /** 招转培危险词 */
    private static final Pattern SCAM = Pattern.compile("培训费|押金|先培训后上岗|包就业");

    /** 超长待机：抗压话术 + 严苛工作制 */
    private static final Pattern ENDURANCE = Pattern.compile("抗压|能吃苦");
    private static final Pattern TIGHT_SCHEDULE = Pattern.compile("单休|大小周");

    /** 具体业务线关键词（启发式，可按需扩充） */
    private static final List<String> BUSINESS_KEYWORDS = List.of(
            "电商", "支付", "金融", "广告", "物流", "供应链", "风控", "游戏", "社交",
            "短视频", "直播", "SaaS", "交易系统", "订单", "推荐", "搜索", "CRM"
    );

    public List<AuditSignal> analyze(String jdText) {
        List<AuditSignal> signals = new ArrayList<>();
        if (jdText == null || jdText.isBlank()) {
            return signals;
        }
        checkSalaryOpacity(jdText, signals);
        checkVagueDuty(jdText, signals);
        checkContradictoryReq(jdText, signals);
        checkUrgencyNoise(jdText, signals);
        checkScamRisk(jdText, signals);
        checkOverworkRisk(jdText, signals);
        return signals;
    }

    /** 规则 1：薪资跨度异常 —— “面议”且无具体范围，或范围上下限差距 > 2 倍 */
    private void checkSalaryOpacity(String jdText, List<AuditSignal> signals) {
        Matcher matcher = SALARY_RANGE.matcher(jdText);
        if (matcher.find()) {
            int low = Integer.parseInt(matcher.group(1));
            int high = Integer.parseInt(matcher.group(2));
            if (low > 0 && high > 2L * low) {
                signals.add(new AuditSignal("salary_opacity", "HIGH",
                        "薪资范围 %d-%dK 上下限差距超过 2 倍，薪资透明度存疑".formatted(low, high)));
            }
        } else if (jdText.contains("面议")) {
            signals.add(new AuditSignal("salary_opacity", "HIGH",
                    "JD 标注薪资“面议”且未给出具体薪资范围（如 X-Y K），薪资不透明"));
        }
    }

    /** 规则 2：职责模糊 —— 出现套话且无具体业务线描述 */
    private void checkVagueDuty(String jdText, List<AuditSignal> signals) {
        if (!VAGUE_DUTY.matcher(jdText).find()) {
            return;
        }
        if (!hasConcreteBusiness(jdText)) {
            signals.add(new AuditSignal("vague_duty", "MEDIUM",
                    "JD 出现“完成相关工作/协助部门事务”等模糊表述，且未描述具体业务线"));
        }
    }

    /** 规则 3：要求矛盾 —— 同时要资深/专家，又要应届/小白/无经验 */
    private void checkContradictoryReq(String jdText, List<AuditSignal> signals) {
        if (SENIOR_REQ.matcher(jdText).find() && JUNIOR_REQ.matcher(jdText).find()) {
            signals.add(new AuditSignal("contradictory_req", "MEDIUM",
                    "JD 同时出现“资深/专家”与“应届/小白/无经验”类要求，岗位定位自相矛盾"));
        }
    }

    /** 规则 4：紧迫话术 —— 急招/火速/urgent 但无具体业务线或项目 */
    private void checkUrgencyNoise(String jdText, List<AuditSignal> signals) {
        if (URGENCY.matcher(jdText).find() && !hasConcreteBusiness(jdText)) {
            signals.add(new AuditSignal("urgency_noise", "LOW",
                    "JD 使用“急招/火速/urgent”等紧迫话术，但未写明具体业务线或项目"));
        }
    }

    /** 规则 5：招转培危险词 —— 培训费/押金/先培训后上岗/包就业 */
    private void checkScamRisk(String jdText, List<AuditSignal> signals) {
        Matcher matcher = SCAM.matcher(jdText);
        if (matcher.find()) {
            signals.add(new AuditSignal("scam_risk", "HIGH",
                    "JD 出现招转培危险词“%s”，存在收费培训或押金诈骗风险".formatted(matcher.group())));
        }
    }

    /** 规则 6：超长待机 —— 抗压/能吃苦 且 单休/大小周 */
    private void checkOverworkRisk(String jdText, List<AuditSignal> signals) {
        if (ENDURANCE.matcher(jdText).find() && TIGHT_SCHEDULE.matcher(jdText).find()) {
            signals.add(new AuditSignal("overwork_risk", "MEDIUM",
                    "JD 同时出现“抗压/能吃苦”与“单休/大小周”描述，存在长期超时加班风险"));
        }
    }

    /** 是否包含具体业务线或项目描述（启发式判断，规则 2、4 共用） */
    private boolean hasConcreteBusiness(String jdText) {
        return jdText.contains("项目") || BUSINESS_KEYWORDS.stream().anyMatch(jdText::contains);
    }
}
