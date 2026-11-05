# 会话记录：LLM 语义提取 Prompt V1 与结构化输出降级

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-05
- 涉及文件：
  - `src/main/java/com/example/jobaudit/service/SignalExtractionService.java`（Prompt 重写、响应模式降级、confidence 兜底）
  - `src/main/java/com/example/jobaudit/domain/AuditSignalView.java`（新建）
  - `src/main/java/com/example/jobaudit/domain/AuditResponse.java`（信号类型调整）
  - `src/main/java/com/example/jobaudit/api/JobAuditController.java`（接入 LLM 语义扫描）
  - `src/test/java/com/example/jobaudit/service/SemanticSignalPromptTest.java`（新建，5 段 JD 演示）
  - `src/test/java/com/example/jobaudit/FaultToleranceTest.java`（新增 confidence 兜底用例）
  - `src/main/resources/application.properties`（移除 strict-json-schema）

---

## 一、Prompt 设计：四要素结构化 System Prompt

### 用户需求（原文）

> 今天我们要完善 SignalExtractionService 里的 Prompt。
> 要求：
> 设计一段用于提取 JD 语义陷阱的 System Prompt。LLM 需要提取的语义信号包括：pua_culture（狼性文化/PUA话术）、overpackaged（过度包装/画大饼）、broad_duty（职责极度宽泛/一人干全栈）。
> Prompt 中必须包含这四个要素：Role（角色：资深求职风控专家）、Task（任务）、Constraints（约束：如"不要提取硬规则已经覆盖的内容"）、Output Format（严格遵守约定的 JSON Schema）。
> 请将这段 Prompt 集成到 SignalExtractionService.extract(String jdText) 方法中。
> 请提供一个简单的测试方法或 Controller 接口，方便我把那 5 段 JD 喂进去跑一遍。

### 设计要点

1. **Role**：资深求职风控专家，做招聘 JD 的"反向尽调"。
2. **Task**：锁定三类语义陷阱 `pua_culture` / `overpackaged` / `broad_duty`，每类给出典型话术示例，减少模型自由发挥。
3. **Constraints**：
   - 只输出一条最严重、最典型的信号；
   - 显式列出 6 条硬规则的豁免清单（salary_opacity / vague_duty / contradictory_req / urgency_noise / scam_risk / overwork_risk），避免与规则引擎重复告警；
   - 不得臆测 JD 中不存在的信息；
   - reason 必须引用 JD 原文关键短语；
   - 三类均未命中时返回 `none`。
4. **Output Format**：明确 JSON 结构、字段名、类型，并禁止 Markdown / 代码块 / 解释性文字。

### 集成后的 SYSTEM_PROMPT（最终版）

```java
private static final SystemMessage SYSTEM_PROMPT = SystemMessage.from("""
        # Role
        你是一名资深求职风控专家，长期为求职者做招聘 JD 的“反向尽调”，\
        擅长识别招聘话术背后隐藏的真实用工风险。

        # Task
        阅读用户提供的招聘 JD 全文，识别规则引擎无法覆盖的语义陷阱，仅限以下三类：
        1. pua_culture：狼性文化、PUA 式管理话术，如“公司是家”“不加班就是不奋斗”\
        “年轻人要能吃亏”“不接受质疑就别来”。
        2. overpackaged：过度包装、画大饼，如“期权很快上市”“行业第一”“未来合伙人”，\
        但缺少可验证的具体信息。
        3. broad_duty：职责极度宽泛、一人干全栈，如同时要求产品、前端、后端、运维、\
        市场、售后且无清晰边界。

        # Constraints
        1. 只输出一条最严重、最典型的语义信号，且判定依据必须能在 JD 原文中找到对应表述。
        2. 不要提取硬规则已经覆盖的内容，出现下列情况一律忽略：
           - 薪资“面议”或薪资范围上下限差距超过 2 倍（salary_opacity）
           - “完成相关工作”“协助部门事务”等职责套话（vague_duty）
           - 同时要求“资深/专家”与“应届/小白/无经验”（contradictory_req）
           - “急招/火速/urgent”但未写明具体业务线（urgency_noise）
           - “培训费/押金/先培训后上岗/包就业”（scam_risk）
           - “抗压/能吃苦”与“单休/大小周”同时出现（overwork_risk）
        3. 不得臆测 JD 中没有的信息，不得输出歧视性或违法判断。
        4. reason 用中文，一句话说明触发依据，并引用 JD 中的关键短语（用引号包裹）。
        5. confidence 为 0~1 的小数，表示你的把握程度。
        6. 三类信号均未命中时，signalType 返回 "none"，reason 返回 "未发现语义陷阱"，\
        confidence 返回 0.0。

        # Output Format
        严格遵守约定的 JSON Schema，只输出 JSON 对象，不要 Markdown、不要代码块、不要任何解释性文字：
        {"signalType": "pua_culture | overpackaged | broad_duty | none", "reason": "中文说明，含原文关键短语", "confidence": 0.0}
        字段不可缺失、不可改名、不可新增。
        """);
```

`extract(String jdText)` 中原有的调用方式不变：`ChatRequest.builder().messages(SYSTEM_PROMPT, UserMessage.from(jdText)).responseFormat(RESPONSE_FORMAT).build()`。

---

## 二、5 段 JD 演示测试

### 测试类

`src/test/java/com/example/jobaudit/service/SemanticSignalPromptTest.java`：

```java
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
```

运行方式：

```powershell
mvn test -Dtest=SemanticSignalPromptTest -Dllm.demo=true
```

默认通过 `Assumptions` 跳过，不影响日常构建，也不消耗模型额度。

### 第一轮结果：5 段全部降级 pending_review

第一次运行全部失败，模型端点返回 **400**：

```
"This response_format type is unavailable now"
```

原因：`application.properties` 配置的是 `deepseek-chat`，该端点只支持 `json_object`，不支持 OpenAI 的 `json_schema` 响应格式。也就是说，请求根本没有进入模型推理，全部被 `extract()` 的兜底逻辑捕获为 `pending_review`——兜底机制工作正常，但提取功能等于没跑。

---

## 三、降级为 json_object 模式

### 决策

面向当前供应商（DeepSeek）选择降级，而非更换模型：

- 请求侧改用 `json_object`；
- 字段与类型约束改由 **Prompt 的 Output Format 段 + 服务端 `parseOrDegrade` 校验** 共同保证；
- 代码注释中记录本次实测原因，避免后人误以为这是"忘了写 Schema"。

### 代码变更

```java
/**
 * 模型输出契约：{"signalType":"...","reason":"...","confidence":0.0}。
 * 当前端点（deepseek-chat）不支持 OpenAI 的 json_schema 模式，实测返回
 * "This response_format type is unavailable now"，故降级为 json_object；
 * 字段与类型约束由 SYSTEM_PROMPT 的 Output Format 段 + parseOrDegrade 服务端校验共同保证。
 */
private static final ResponseFormat RESPONSE_FORMAT = ResponseFormat.builder()
        .type(ResponseFormatType.JSON)
        .build();
```

`application.properties`：

```properties
langchain4j.open-ai.chat-model.model-name=deepseek-chat
# deepseek-chat 不支持 json_schema，结构化约束改由 Prompt + 服务端校验保证，无需 strict-json-schema
langchain4j.open-ai.chat-model.log-requests=true
```

已删除 `strict-json-schema=true`：json_object 模式下该配置不生效，保留反而会造成"仍有硬约束"的错觉。

### 第二轮结果：5 段全部成功

| # | JD 类型 | signalType | confidence | status |
|---|---|---|---|---|
| 1 | 狼性/PUA 话术 | `pua_culture` | 0.98 | OK |
| 2 | 画大饼/过度包装 | `overpackaged` | 0.95 | OK |
| 3 | 一人干全栈 | `broad_duty` | 0.97 | OK |
| 4 | 正常电商后端 JD | `none` | 0.0 | OK |
| 5 | 面议 + 单休 + 抗压（仅硬规则覆盖） | `none` | 0.0 | OK |

### 结果分析：漏报与误报

**误报**：本轮未观察到明显误报。第 4 段正常 JD 正确返回 `none`；第 5 段虽然踩了 4 条硬规则（面议、急招、单休、抗压），LLM 仍正确返回 `none`，说明 Constraints 中"不要重复提取硬规则已覆盖的内容"确实生效。

**漏报 / 单向性风险**（需要后续用真实 JD 扩大样本量验证）：

1. Prompt 约束"只输出一条最严重、最典型的信号"，因此一条 JD 同时存在多处语义陷阱时只报一个。上面的第 5 段就同时具备 PUA 话术与职责模糊，但只输出了 `pua_culture`。这是"降噪"与"召回"的取舍，需要确认产品侧希望的是"打标"还是"全量列出"。
2. 三类信号的判定依赖模型对典型话术的识别，对隐晦表述（如不点名但暗示无偿加班）的召回率尚未量化。
3. 第 4 段（20-30K、职责具体）返回 `none` 属于正确行为，但如果后续规则集扩充，需要重新回归这批样例，避免规则与 LLM 判断重叠。

**建议的后续验证**：从真实招聘平台抓取 30~50 条 JD，人工标注期望信号，统计三类信号的命中率与误报率；同时对同一 JD 重复调用 3 次，观察 `signalType` 稳定性。

---

## 四、同日发现并修复的两个问题

### 4.1 截图 JD 的 `signals: []` 不是 bug

用户反馈某次 POST 返回 `signals` 为空，怀疑 LLM 异常打断了规则引擎。排查结论：

- 该 JD 对 6 条硬规则实测 `命中数=0`：无薪资信息、无"完成相关工作/协助部门事务"字面、有"抗压"但无"单休/大小周"等；
- 当时 `SignalExtractionService` 尚未被 Controller 引用，LLM 压根不在请求链路里，不可能中断；
- 控制台无异常，接口返回 200，主记录已正常入库。

因此空数组是当前规则集的正确输出。随后按用户选择，将 LLM 语义扫描正式接入 `create()`。

### 4.2 LLM 信号缺失 source / confidence

**根因**：`POST /api/audits` 的响应模型是 `AuditResponse(List<AuditSignal>)`，而 `AuditSignal` 只有 `signalType / severity / reason` 三个字段，序列化时自然没有 source 与 confidence；`GET /api/audits/{id}` 用的是 `AuditSignalRecord`，一直是有这两个字段的。

**修复**：

新增统一视图 DTO：

```java
public record AuditSignalView(String signalType, String severity, String reason,
                              String source, BigDecimal confidence) {

    public static AuditSignalView fromRule(AuditSignal signal) {
        return new AuditSignalView(signal.signalType(), signal.severity(), signal.reason(), "RULE", null);
    }

    public static AuditSignalView fromLlm(ExtractedSignal signal, String severity) {
        return new AuditSignalView(signal.signalType(), severity, signal.reason(),
                "LLM", BigDecimal.valueOf(signal.confidence()));
    }
}
```

`AuditResponse` 的 `signals` 改为 `List<AuditSignalView>`；Controller 中规则信号与 LLM 信号统一走两个工厂方法组装，落库逻辑不变。

**confidence 兜底**：原 `parseOrDegrade` 把 `confidence == null` 视为字段缺失而整条降级，改为只有 `signalType` / `reason` 缺失才降级，`confidence` 缺失取默认值：

```java
private static final double DEFAULT_CONFIDENCE = 0.8;

Double confidence = parsed.confidence() == null ? DEFAULT_CONFIDENCE : parsed.confidence();
```

配套新增测试用例（`FaultToleranceTest#shouldDefaultConfidenceWhenModelOmitsIt`），验证"只缺 confidence → status=OK 且取 0.8"；该测试类 2 个用例全部通过。

**验证结果**（真实启动 + 真实调用）：

POST 一条同时触发硬规则与 LLM 的 JD，返回：

```json
{"signalType":"salary_opacity","severity":"HIGH","source":"RULE","confidence":null}
{"signalType":"urgency_noise","severity":"LOW","source":"RULE","confidence":null}
{"signalType":"overwork_risk","severity":"MEDIUM","source":"RULE","confidence":null}
{"signalType":"pua_culture","severity":"HIGH","source":"LLM","confidence":0.95}
```

查询接口确认入库：LLM 信号 `confidence=0.95`、`source="LLM"`；规则信号 `source="RULE"`、`confidence=null`。

规则信号置信度保持 `null` 是有意设计：规则引擎没有概率模型，填造数值会误导前端排序。

---

## 五、遗留事项

1. **signalType 白名单未校验**：模型若返回约定外的类型（如自造命名），服务端目前会照单接收。可在 `parseOrDegrade` 增加白名单校验，不合法则降级。
2. **响应延迟**：`POST /api/audits` 现在多了一次 LLM 调用（约 1~1.5 秒）。若前端在意，可改为先返回规则结果、LLM 结果异步补充。
3. **供应商能力差异**：换到支持 `json_schema` 的端点（如 OpenAI）时，应把 `RESPONSE_FORMAT` 换回带 `JsonSchema` 的实现并恢复 `strict-json-schema` 配置，其余代码无需改动。
4. **样本量不足**：5 段样例只能做冒烟验证，漏报/误报率需要更大规模的真实 JD 评测。
