package com.example.jobaudit.rule;

import com.example.jobaudit.domain.AuditSignal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

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
}
