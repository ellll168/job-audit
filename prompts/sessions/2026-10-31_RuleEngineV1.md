# 会话记录：规则引擎 V1（AuditSignal + AuditRuleEngine）

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-02
- 涉及文件：
  - `src/main/java/com/example/jobaudit/domain/AuditSignal.java`
  - `src/main/java/com/example/jobaudit/rule/AuditRuleEngine.java`
  - `src/test/java/com/example/jobaudit/rule/AuditRuleEngineTest.java`

---

## 第 1 轮：实现 AuditSignal 与 AuditRuleEngine

### 用户提问

> @.trae-context.md 帮我实现规则引擎的第一批代码。
> 要求：
>
> 在 com.example.jobaudit.domain 包下，用 Java 17 的 record 定义一个 AuditSignal，包含字段：signalType (String), severity (String), reason (String)。
>
> 在 com.example.jobaudit.rule 包下，创建一个 AuditRuleEngine 类（使用 @Component 注解交给 Spring 管理）。
>
> 在 AuditRuleEngine 中写一个方法 public List&lt;AuditSignal&gt; analyze(String jdText)。
>
> 在这个方法里先实现两条硬规则：
>
> 规则 1（薪资跨度异常）：如果 JD 中出现"面议"且没有具体的薪资范围（如 X-Y K），或者提取出的薪资范围上下限差距 &gt; 2 倍，输出 signalType="salary_opacity", severity="HIGH", reason 说明触发原因。（请用正则表达式提取薪资）。
>
> 规则 2（职责模糊）：如果 JD 中出现"完成相关工作"或"协助部门事务"，且无具体业务线描述，输出 signalType="vague_duty", severity="MEDIUM", reason 说明触发原因。
>
> 不使用 Lombok。请给出完整的代码。

### 处理过程

1. 按 `.trae-context.md` 包规范新建 `domain` 与 `rule` 两个包（此前仅有 `api` 包）。
2. `AuditSignal` 用 Java 17 record 定义，编译器自动生成构造器/访问器/`equals/hashCode/toString`，无需 Lombok。
3. `AuditRuleEngine` 设计要点：
   - 薪资正则 `(\d+)\s*[-~]\s*(\d+)\s*[kK]`：兼容 `-` 与 `~`、大小写 K，捕获上下限（单位 K）。
   - 规则 1 两分支互斥：提取到范围时只判 `high > 2 * low`；无范围且含"面议"才报"薪资不透明"，避免重复报警。
   - 规则 2 先匹配套话（完成相关工作/协助部门事务），再用业务线关键词表（电商/支付/风控/交易系统等启发式词表）判断是否"无具体业务线描述"，两个条件同时满足才出信号。
   - `analyze` 入口对 null/空白文本直接返回空列表，规则引擎永不抛异常（符合项目"不裸抛 500"约定）。

### 生成的完整代码

`src/main/java/com/example/jobaudit/domain/AuditSignal.java`：

```java
package com.example.jobaudit.domain;

/**
 * 审计信号：规则引擎命中风险后输出的结构化结果。
 */
public record AuditSignal(String signalType, String severity, String reason) {
}
```

`src/main/java/com/example/jobaudit/rule/AuditRuleEngine.java`：

```java
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

    /** 规则 1：薪资跨度异常 —— "面议"且无具体范围，或范围上下限差距 > 2 倍 */
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
                    "JD 标注薪资"面议"且未给出具体薪资范围（如 X-Y K），薪资不透明"));
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
                    "JD 出现"完成相关工作/协助部门事务"等模糊表述，且未描述具体业务线"));
        }
    }
}
```

### 结果

`mvn -q -DskipTests compile` 编译通过（退出码 0）。

---

## 第 2 轮：为规则引擎编写 JUnit 5 单元测试

### 用户提问

> 为了防止规则引擎以后被改坏，请帮我写一个标准的 JUnit 5 单元测试类。
> 要求：
>
> 类名 AuditRuleEngineTest，放在 src/test/java/com/example/jobaudit/rule/ 目录下。
>
> 使用 @SpringBootTest 注入 AuditRuleEngine。
>
> 写一个测试方法验证"薪资异常"：输入包含"薪资面议"的假数据，断言输出结果中必须包含 signalType="salary_opacity"。
>
> 写一个测试方法验证"职责模糊"：输入包含"完成相关工作"的假数据，断言输出中必须包含 signalType="vague_duty"。
>
> 请给出完整的测试代码，并说明运行方式。

### 处理过程

1. 先检查 `application.properties`：项目配置了 MySQL 数据源 + Flyway，`@SpringBootTest` 会真实连接数据库，本地运行需保证 MySQL 已启动（该约束已在交付说明中告知用户）。
2. 测试设计要点：
   - 断言用 `anyMatch` 只判断"存在目标 signalType"，不断言列表大小，后续新增规则时老测试不会误挂。
   - 职责模糊用例的假数据刻意避开所有业务线关键词，确保命中规则 2 分支。
   - 断言失败消息中打印实际 `signals`，便于排查。

### 生成的完整代码

`src/test/java/com/example/jobaudit/rule/AuditRuleEngineTest.java`：

```java
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
```

### 结果

执行 `mvn -q test -Dtest=AuditRuleEngineTest`，两个测试全部通过（退出码 0）。日志同时确认 Flyway 已成功应用 `V1__init.sql`（`Current version of schema job_audit_db: 1`）。

### 运行方式

```powershell
mvn test -Dtest=AuditRuleEngineTest      # 只跑这个测试类
mvn test                                  # 跑全部测试
```
