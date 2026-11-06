package com.example.jobaudit.rule;

import com.example.jobaudit.domain.AuditSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AuditRuleEngineTest {

    @Autowired
    private AuditRuleEngine ruleEngine;

    @Test
    void shouldDetectSalaryOpacityWhenSalaryNegotiableWithoutRange() {
        String jdText = "急招 Java 开发工程师，薪资面议，福利待遇优厚，期待你的加入！";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "应命中 signalType=salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldDetectVagueDutyWhenDutyAmbiguousWithoutBusinessLine() {
        String jdText = "岗位职责：完成相关工作，协助部门事务。岗位要求：本科及以上学历，工作认真负责。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "vague_duty".equals(s.signalType())),
                "应命中 signalType=vague_duty，实际输出：" + signals);
    }

    @Test
    void shouldDetectVagueDutyForLeaderAssignedVariant() {
        String jdText = "岗位职责：偶尔兼顾行政事务及领导交办的其他工作。任职要求：本科及以上学历，沟通能力良好。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "vague_duty".equals(s.signalType())),
                "“领导交办的其他工作”应命中 signalType=vague_duty，实际输出：" + signals);
    }

    @Test
    void shouldDetectVagueDutyForArrangedRelatedWorkVariant() {
        String jdText = "岗位职责：处理上级安排的相关工作，配合团队完成日常事务。任职要求：工作认真负责，稳定性好。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "vague_duty".equals(s.signalType())),
                "“安排的相关工作”应命中 signalType=vague_duty，实际输出：" + signals);
    }

    @Test
    void shouldDetectVagueDutyForCompanyArrangedRelatedWorkVariant() {
        String jdText = "岗位职责：负责公司安排的其他相关工作，配合团队完成日常事务。任职要求：本科及以上学历，工作认真负责。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "vague_duty".equals(s.signalType())),
                "“公司安排的其他相关工作”应命中 signalType=vague_duty，实际输出：" + signals);
    }

    @Test
    void shouldDetectSalaryOpacityForUppercaseKRange() {
        String jdText = "招聘后端开发工程师，负责订单系统开发，要求三年以上经验，薪资 8K-30K，六险一金。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "8K-30K 跨度超 2 倍，应命中 signalType=salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldDetectSalaryOpacityForLowercaseKRange() {
        String jdText = "招聘后端开发工程师，负责支付系统开发与维护，薪资 8k-30k，福利完善。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "8k-30k 跨度超 2 倍，应命中 signalType=salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldDetectSalaryOpacityForPlainNumberRange() {
        String jdText = "招聘 Java 开发工程师，负责交易系统开发与维护，薪资 8000-30000 元，五险一金。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "8000-30000 跨度超 2 倍，应命中 signalType=salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldDetectSalaryOpacityForWanUnitRange() {
        String jdText = "招聘后端开发工程师，负责订单系统开发维护，薪资1万-3万，六险一金。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "1万-3万 跨度 3 倍，应命中 salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldNotFlagReasonableWanUnitRange() {
        String jdText = "招聘后端开发工程师，负责订单系统开发维护，薪资2万-3万，五险一金。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertFalse(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "2万-3万 跨度 1.5 倍，不应命中 salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldNotTreatYearRangeAsSalary() {
        String jdText = "招聘 Java 开发工程师，要求 3-5年 经验，薪资 10000-12000 元，五险一金。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertFalse(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "3-5年 是年限而非薪资，且 10000-12000 跨度正常，不应命中 salary_opacity，实际输出：" + signals);
    }

    @Test
    void shouldDetectInformationMissingWhenJdOnlyHasPunctuationPadding() {
        String jdText = "岗位：Java。要求：高薪，联系我。面议。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "information_missing".equals(s.signalType())),
                "剔除标点后有效字符不足 20，应命中 information_missing，实际输出：" + signals);
        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "兜底信号应与 salary_opacity 并排输出，实际输出：" + signals);
    }

    @Test
    void shouldDetectInformationMissingAtExactlyTwentyChars() {
        String jdText = "招聘后端开发工程师负责订单系统开发维护岗";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "information_missing".equals(s.signalType())),
                "有效长度恰好 20 也算信息缺失，应命中 information_missing，实际输出：" + signals);
    }

    @Test
    void shouldDetectInformationMissingWhenJdTooShort() {
        String jdText = "招Java，高薪，联系我。面议。";

        List<AuditSignal> signals = ruleEngine.analyze(jdText);

        assertTrue(signals.stream().anyMatch(s -> "information_missing".equals(s.signalType())),
                "极短 JD 应命中 information_missing，实际输出：" + signals);
        AuditSignal infoSignal = signals.stream()
                .filter(s -> "information_missing".equals(s.signalType()))
                .findFirst()
                .orElseThrow();
        assertEquals("HIGH", infoSignal.severity());
        assertTrue(signals.stream().anyMatch(s -> "salary_opacity".equals(s.signalType())),
                "兜底信号不再提前返回，“面议”应同时命中 salary_opacity，实际输出：" + signals);
    }
}
