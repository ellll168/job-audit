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
        boolean hasBusinessLine = BUSINESS_KEYWORDS.stream().anyMatch(jdText::contains);
        if (!hasBusinessLine) {
            signals.add(new AuditSignal("vague_duty", "MEDIUM",
                    "JD 出现“完成相关工作/协助部门事务”等模糊表述，且未描述具体业务线"));
        }
    }
}
