package com.example.jobaudit.rule;

import com.example.jobaudit.domain.AuditSignal;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计规则引擎：对 JD 文本执行硬规则扫描，输出命中的审计信号。
 */
@Component
public class AuditRuleEngine {

    /**
     * 薪资范围：支持 10-20K、8k~30k、8K-30K、8000-30000、1.5万-3万 等写法。
     * 单位 K/千（=1000）、万（=10000）可选；无单位时只有数值 >= 1000 才视为金额，
     * 避免把“3-5年”“3-5人”误判为薪资。末尾负向断言排除“年/月/岁”，进一步降低年限类误报。
     */
    private static final Pattern SALARY_RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*([kK千万萬])?\\s*[-~～至]\\s*(\\d+(?:\\.\\d+)?)\\s*([kK千万萬])?(?!\\s*[年月岁])");

    /** 薪资单位换算基数（统一折算为元后比较，兼容 8K-3万 这类混合写法） */
    private static final BigDecimal K_UNIT = BigDecimal.valueOf(1000);
    private static final BigDecimal WAN_UNIT = BigDecimal.valueOf(10000);

    /** 薪资跨度容忍倍数：上下限差距超过 2 倍视为不透明 */
    private static final BigDecimal MAX_SPAN_RATIO = BigDecimal.valueOf(2);

    /**
     * 职责模糊的典型套话与变体：
     * 完成相关工作 / 领导交办的其他工作 / 上级安排的相关工作 / 公司安排的其他相关工作 /
     * 协助部门事务 / 兼顾行政事务。
     * “交办/安排”前的责任主体可选，“的”“其他”“相关”均可省略或组合，避免漏掉“安排的其他相关工作”这类写法。
     */
    private static final Pattern VAGUE_DUTY = Pattern.compile(
            "完成相关工作|(?:领导|公司|上级|部门|老板)?(?:交办|安排)的?(?:其他)?(?:相关)?工作|协助部门事务|兼顾行政事务");

    /** JD 剔除标点与空白后的有效长度下限，等于或低于该值视为信息极度缺失 */
    private static final int MIN_JD_LENGTH = 20;

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
        if (jdText == null) {
            return signals;
        }
        // 兜底规则（虫子 5）：信息量过低本身即为风险，允许与其它命中的规则共存。
        // 长度统计需剔除标点与各类空白（含中文标点、全角空格），否则“岗位：Java。要求：高薪，联系我。面议。”
        // 这类实际只有十几个有效字符的 JD 会被标点撑到 20 字以上而漏报。
        String compact = jdText.replaceAll("[\\p{P}\\p{Z}]", "");
        if (compact.length() <= MIN_JD_LENGTH) {
            signals.add(new AuditSignal("information_missing", "HIGH",
                    "JD信息极度缺失，存在盲盒风险"));
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
        boolean foundValidRange = false;
        while (matcher.find()) {
            BigDecimal low = new BigDecimal(matcher.group(1));
            BigDecimal high = new BigDecimal(matcher.group(3));
            String lowUnit = matcher.group(2);
            String highUnit = matcher.group(4);
            boolean hasUnit = lowUnit != null || highUnit != null;
            // 无单位且数值过小，多半是年限/人数，不作为薪资参与判断
            if (!hasUnit && (low.compareTo(K_UNIT) < 0 || high.compareTo(K_UNIT) < 0)) {
                continue;
            }
            foundValidRange = true;
            // 统一折算为元再比较，兼容“10-20K”（下限缺省单位）与“8K-3万”（两侧单位不同）
            BigDecimal lowYuan = toYuan(low, lowUnit, highUnit);
            BigDecimal highYuan = toYuan(high, highUnit, lowUnit);
            if (lowYuan.signum() > 0 && highYuan.compareTo(lowYuan.multiply(MAX_SPAN_RATIO)) > 0) {
                signals.add(new AuditSignal("salary_opacity", "HIGH",
                        "薪资范围 %s 上下限差距超过 2 倍，薪资透明度存疑".formatted(matcher.group())));
                return;
            }
        }
        if (!foundValidRange && jdText.contains("面议")) {
            signals.add(new AuditSignal("salary_opacity", "HIGH",
                    "JD 标注薪资“面议”且未给出具体薪资范围（如 X-Y K），薪资不透明"));
        }
    }

    /**
     * 把薪资数值折算为元。
     * 本侧无单位时沿用另一侧单位（“10-20K”即 10K-20K）；两侧都无单位则按元处理。
     */
    private BigDecimal toYuan(BigDecimal value, String unit, String otherUnit) {
        String effectiveUnit = unit != null ? unit : otherUnit;
        if (effectiveUnit == null) {
            return value;
        }
        return "万".equals(effectiveUnit) || "萬".equals(effectiveUnit)
                ? value.multiply(WAN_UNIT)
                : value.multiply(K_UNIT);
    }

    /** 规则 2：职责模糊 —— 出现套话及变体，且无具体业务线描述 */
    private void checkVagueDuty(String jdText, List<AuditSignal> signals) {
        Matcher matcher = VAGUE_DUTY.matcher(jdText);
        if (!matcher.find()) {
            return;
        }
        if (!hasConcreteBusiness(jdText)) {
            signals.add(new AuditSignal("vague_duty", "MEDIUM",
                    "JD 出现“%s”等模糊职责表述，且未描述具体业务线".formatted(matcher.group())));
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
