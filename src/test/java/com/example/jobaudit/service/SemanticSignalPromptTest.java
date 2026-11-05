package com.example.jobaudit.service;

import com.example.jobaudit.domain.ExtractedSignal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 语义 Prompt 效果演示：把 5 段真实风格的 JD 依次喂给 LLM，打印识别结果。
 * 默认跳过（避免每次构建都消耗模型额度），手动运行：
 * mvn test -Dtest=SemanticSignalPromptTest -Dllm.demo=true
 */
@SpringBootTest
class SemanticSignalPromptTest {

    @Autowired
    SignalExtractionService service;

    /** 5 段样例：前 3 段分别对应三类语义陷阱，第 4 段为正常 JD，第 5 段只含硬规则已覆盖的问题 */
    private static final List<String> JD_SAMPLES = List.of(
            "我们是狼性团队，公司就是你的家。创业阶段大家一起拼搏，不加班就是不奋斗，"
                    + "年轻人要能吃亏，不接受质疑的请绕道。",
            "我们是行业第一的准独角兽，期权很快上市，未来三年估值翻十倍，"
                    + "加入即成为合伙人，一起改变世界。",
            "岗位职责：独立负责产品方案设计、前端页面开发、后端服务架构、数据库运维、"
                    + "服务器部署、市场投放与客户售后，一人搞定全流程。",
            "招聘 Java 后端工程师，负责电商交易系统订单服务的开发与维护，"
                    + "要求 3 年以上经验，薪资 20-30K。",
            "急招 Java 开发，薪资面议，单休，要求能吃苦抗压，完成相关工作。"
    );

    @Test
    void runFiveJdSamples() {
        Assumptions.assumeTrue(Boolean.getBoolean("llm.demo"),
                "手动演示测试，默认跳过。运行方式：mvn test -Dtest=SemanticSignalPromptTest -Dllm.demo=true");

        for (int i = 0; i < JD_SAMPLES.size(); i++) {
            String jd = JD_SAMPLES.get(i);
            ExtractedSignal signal = service.extract(jd);
            System.out.printf("""
                    --- 样例 %d ---
                    JD：%s
                    结果：signalType=%s, confidence=%s, status=%s
                    理由：%s

                    """, i + 1, jd, signal.signalType(), signal.confidence(), signal.status(), signal.reason());
        }
    }
}
