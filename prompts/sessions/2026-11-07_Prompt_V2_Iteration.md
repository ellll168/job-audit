# 会话记录：Prompt V2 迭代与规则引擎边界修复

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-06
- 涉及文件：
  - `src/main/java/com/example/jobaudit/rule/AuditRuleEngine.java`
  - `src/main/java/com/example/jobaudit/service/SignalExtractionService.java`
  - `src/test/java/com/example/jobaudit/rule/AuditRuleEngineTest.java`
  - `src/test/java/com/example/jobaudit/service/SemanticSignalPromptTest.java`

---

## 一、规则引擎修复（依据 prompt-review-v1.md）

### 用户需求（原文）

> 根据我昨天的测试记录（见 prompts/prompt-review-v1.md），请对 AuditRuleEngine 进行代码修复：
> 1. 修复 vague_duty（职责模糊）：扩充正则表达式，除了"完成相关工作"，还要能匹配"领导交办的其他工作"、"安排的相关工作"等变体。
> 2. 修复 salary_opacity（薪资不透明）：优化提取薪资的正则。要能识别"8K-30K"、"8k-30k"、"8000-30000"等各种格式，并将其转化为数字比较。如果上下限相差大于 2 倍，必须触发信号。
> 3. 增加兜底规则（虫子5）：如果传入的 jdText 去除空格后长度小于 20 个字符，直接输出一条 information_missing (HIGH) 信号，提示"JD信息极度缺失，存在盲盒风险"。
> 请给出修复后的完整 AuditRuleEngine 代码，并附带上你的测试用例。

### 1.1 vague_duty 正则扩充

旧正则只覆盖两个字面短语，扩充为结构式匹配：

```java
/**
 * 职责模糊的典型套话与变体：
 * 完成相关工作 / 领导交办的其他工作 / 上级安排的相关工作 / 公司安排的其他相关工作 /
 * 协助部门事务 / 兼顾行政事务。
 */
private static final Pattern VAGUE_DUTY = Pattern.compile(
        "完成相关工作|(?:领导|公司|上级|部门|老板)?(?:交办|安排)的?(?:其他)?(?:相关)?工作|协助部门事务|兼顾行政事务");
```

设计：责任主体可选，`的`/`其他`/`相关` 三个修饰成分各自独立可选，兼容"领导交办的其他工作""上级安排的相关工作""公司安排的其他相关工作"等写法。

### 1.2 salary_opacity 薪资正则重写

```java
/**
 * 薪资范围：支持 10-20K、8k~30k、8K-30K、8000-30000 等写法。
 * 单位 K/千 可选；无单位时只有数值 >= 1000 才视为金额，避免把"3-5年""3-5人"误判为薪资。
 * 末尾负向断言排除"年/月/岁"。
 */
private static final Pattern SALARY_RANGE = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*([kK千])?\\s*[-~～至]\\s*(\\d+(?:\\.\\d+)?)\\s*([kK千])?(?!\\s*[年月岁])");

private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
private static final BigDecimal MAX_SPAN_RATIO = BigDecimal.valueOf(2);
```

判断逻辑改为遍历所有匹配：

```java
while (matcher.find()) {
    BigDecimal low = new BigDecimal(matcher.group(1));
    BigDecimal high = new BigDecimal(matcher.group(3));
    boolean hasUnit = matcher.group(2) != null || matcher.group(4) != null;
    // 无单位且数值过小，多半是年限/人数，不作为薪资参与判断
    if (!hasUnit && (low.compareTo(THOUSAND) < 0 || high.compareTo(THOUSAND) < 0)) {
        continue;
    }
    foundValidRange = true;
    if (low.signum() > 0 && high.compareTo(low.multiply(MAX_SPAN_RATIO)) > 0) {
        signals.add(new AuditSignal("salary_opacity", "HIGH",
                "薪资范围 %s 上下限差距超过 2 倍，薪资透明度存疑".formatted(matcher.group())));
        return;
    }
}
if (!foundValidRange && jdText.contains("面议")) { ... }
```

要点：

- 只有"存在至少一个有效薪资范围"时才跳过"面议"判断，保持原有互斥语义；
- 数值比较用 `BigDecimal`，避免整数解析和浮点误差；
- 用 `matcher.group()` 回显原文（如 `8K-30K`），reason 可读性优于拼接数字。

**未覆盖项（当时已明确告知用户）**：`万` 单位（如"1.5万-3万"）不在本次范围内，属于遗留项。

### 1.3 information_missing 兜底

```java
public List<AuditSignal> analyze(String jdText) {
    List<AuditSignal> signals = new ArrayList<>();
    if (jdText == null) {
        return signals;
    }
    String compact = jdText.replaceAll("[\\s\\u3000]", "");
    if (compact.length() < MIN_JD_LENGTH) {
        signals.add(new AuditSignal("information_missing", "HIGH", "JD信息极度缺失，存在盲盒风险"));
    }
    checkSalaryOpacity(jdText, signals);
    // ... 其余 5 条规则
    return signals;
}
```

首版实现带 `return`（极短文本只输出兜底信号）。用户随后要求："按这个方案修改，去掉 return 让兜底信号与其他规则共存"，于是改为 `signals.add()` 后继续执行全部规则。

---

## 二、V2 Prompt 设计与 Few-shot

### 用户需求（原文）

> 现在请升级 SignalExtractionService 里的 System Prompt 到 V2 版本。
> 要求：在 Prompt 中新增以下 Few-shot（少样本示例）来教模型：
> 【新增分类定义与示例】
> overpackaged（过度包装）：指只画大饼不提实质的内容。
> 示例输入："我们提供广阔的发展空间、扁平化管理、零食下午茶，薪资面议。"
> 示例输出：{"signalType":"overpackaged", "reason":"JD大量描述公司福利和期权，但未给出具体业务方向和明确薪酬", "confidence":0.85}
> salary_mismatch（薪资要求错位）：指要求极高（全栈、总监级）但薪资极低。
> 示例输入："要求精通Java、前端、运维、算法，薪资4-6K。"
> 示例输出：{"signalType":"salary_mismatch", "reason":"岗位要求极高，涵盖全栈及算法，但薪资水平远低于市场合理区间", "confidence":0.9}
> 请更新 Service 里的 Prompt 字符串，确保把上述示例加入进去。同时，要求模型输出时，必须严格遵守 JSON 格式。

### 实现要点

1. Task 从 3 类扩为 4 类，新增 `salary_mismatch`；`overpackaged` 定义收紧为"只描述福利/期权/发展空间，不提具体业务方向或薪酬"。
2. 新增独立段 `# Few-shot Examples`，两组示例原样写入，并追加一句"不要输出示例中的内容"，防止模型在无关输入上照抄示例。
3. Output Format 的 signalType 枚举同步加入 `salary_mismatch`，并明确"不可缺失、不可改名、不可新增"。
4. Constraints 增加边界说明：

```
注意：硬规则只判断薪资是否透明（面议/跨度异常），不判断薪资与要求是否匹配；
要求极高但薪资极低，仍应提取 salary_mismatch。
```

这条是必要的——否则模型会把"4-6K + 高要求"误判为硬规则已覆盖的 `salary_opacity` 而放弃提取。

5. 同时把类注释里过时的"JsonSchema + strict 强制约束"改为实际的"json_object + Prompt 契约 + 服务端校验"。

### 验证结果

演示测试从 7 段扩到 7 段（新增 2 组 Few-shot 原样输入），实跑 `Tests run: 1, Failures: 0, Errors: 0`：

| # | 样例 | 结果 | confidence |
|---|---|---|---|
| 1 | 狼性/PUA 话术 | `pua_culture` | 0.95 |
| 2 | 行业第一/期权上市 | `overpackaged` | 0.90 |
| 3 | 一人干全栈 | `broad_duty` | 0.95 |
| 4 | 要求精通Java/前端/运维/算法，薪资4-6K | `salary_mismatch` | 0.92 |
| 5 | 正常电商后端 JD | `none` | 0.0 |
| 6 | 面议+单休+抗压（仅硬规则覆盖） | `none` | 0.0 |
| 7 | 发展空间/扁平化/零食下午茶，薪资面议 | `overpackaged` | 0.85 |

第 4、7 条是 Few-shot 原样输入，输出与示例一致；第 6 条对照组仍为 `none`，说明硬规则豁免约束没被新分类破坏。

---

## 三、"安排的其他相关工作"漏报修复

### 现象

用户反馈：一段明确写了"负责公司安排的其他相关工作"的 JD，硬规则 `vague_duty` 没有触发。

### 根因

不是改坏，而是变体没覆盖全。当时正则的三条分支都进不去：

- `安排的相关工作` 是字面精确匹配，中间多一个"其他"就断；
- `领导(?:交办|安排)...` 要求主语必须是"领导"，这里是"公司"；
- `的?`、`其他?` 两个可选组无法表达"的 + 其他 + 相关"同时出现。

### 修复

即第一章 1.1 的结构式正则。修复后覆盖：

| 表述 | 结果 |
|---|---|
| 完成相关工作 | 命中 |
| 安排的相关工作 | 命中 |
| 上级安排的相关工作 | 命中 |
| 领导交办的其他工作 | 命中 |
| 公司安排的其他相关工作 | 命中（本次目标） |
| 公司安排的其他工作 | 命中 |

### 豁免逻辑确认

`vague_duty` 仍受 `hasConcreteBusiness()` 约束：

```java
private boolean hasConcreteBusiness(String jdText) {
    return jdText.contains("项目") || BUSINESS_KEYWORDS.stream().anyMatch(jdText::contains);
}
```

即：命中模糊表述但 JD 同时写了具体业务线（电商/支付/订单/交易系统等），**不报** `vague_duty`。这是原有设计（有业务线描述就不算模糊），修复正则不会绕过它。已向用户确认：如果那段 JD 恰好含业务关键词，未触发是预期行为，不是 bug。同一条 `hasConcreteBusiness()` 也被规则 4 `urgency_noise` 复用。

---

## 四、极短文本边界：标点虚增长度与 20 字门槛

### 现象

用户测试 `岗位：Java。要求：高薪，联系我。面议。`：`salary_opacity` 正常触发，`information_missing` 没触发。

### 根因

该文本逐字计数 21 个字符，但其中 5 个是中文标点（`：` `。` `，`），有效字符仅 15 个。旧代码只删除空白：

```java
String compact = jdText.replaceAll("[\\s\\u3000]", "");
```

Java 的 `\s` 只匹配 ASCII 空白，中文标点一个都删不掉，于是长度被标点撑过 20，兜底漏报。

同时确认了用户的第二个怀疑点：兜底逻辑**不在**任何 `return` 分支里（`signals.add()` 之后继续跑 6 条规则），`salary_opacity` 与它本来就应并排输出。

### 修复

```java
// 长度统计需剔除标点与各类空白（含中文标点、全角空格）
String compact = jdText.replaceAll("[\\p{P}\\p{Z}]", "");
if (compact.length() <= MIN_JD_LENGTH) {
    signals.add(new AuditSignal("information_missing", "HIGH", "JD信息极度缺失，存在盲盒风险"));
}
```

- `\p{P}` 覆盖全部 Unicode 标点（全角冒号、句号、逗号、括号、引号等）；
- `\p{Z}` 覆盖各类空白分隔符（含全角空格 U+3000），比 `\s` 完整，顺带替代了原来的 `\u3000` 特判。

修复后同一文本的 `compact` 为 `岗位Java要求高薪联系我面议`（15 字），兜底正常触发。

### 20 字边界的业务决策

首版阈值是严格小于（`< 20`）。向用户确认产品口径后，用户决定："把阈值改成 <=20"。

最终口径：**剔除标点与空白后，有效字符数 ≤ 20 报 `information_missing`；21 及以上不报**。常量注释同步改为"等于或低于该值视为信息极度缺失"。

---

## 五、单元测试成果

全部位于 `AuditRuleEngineTest`（`@SpringBootTest`），最终 `Tests run: 12, Failures: 0, Errors: 0`。

| 测试方法 | 验证点 |
|---|---|
| `shouldDetectSalaryOpacityWhenSalaryNegotiableWithoutRange` | "面议"且无范围 → salary_opacity |
| `shouldDetectVagueDutyWhenDutyAmbiguousWithoutBusinessLine` | "完成相关工作/协助部门事务" → vague_duty |
| `shouldDetectVagueDutyForLeaderAssignedVariant` | "领导交办的其他工作" → vague_duty |
| `shouldDetectVagueDutyForArrangedRelatedWorkVariant` | "上级安排的相关工作" → vague_duty |
| `shouldDetectVagueDutyForCompanyArrangedRelatedWorkVariant` | "公司安排的其他相关工作" → vague_duty（本次新增） |
| `shouldDetectSalaryOpacityForUppercaseKRange` | 8K-30K（3.75 倍）→ salary_opacity |
| `shouldDetectSalaryOpacityForLowercaseKRange` | 8k-30k → salary_opacity |
| `shouldDetectSalaryOpacityForPlainNumberRange` | 8000-30000 → salary_opacity |
| `shouldNotTreatYearRangeAsSalary` | "3-5年 + 10000-12000" 不误报 |
| `shouldDetectInformationMissingAtExactlyTwentyChars` | 恰好 20 有效字符 → 命中（验证 `<=` 边界） |
| `shouldDetectInformationMissingWhenJdOnlyHasPunctuationPadding` | 标点虚增长度的文本 → information_missing 与 salary_opacity 并排输出 |
| `shouldDetectInformationMissingWhenJdTooShort` | 极短 JD → information_missing/HIGH + salary_opacity 共存 |

断言风格统一为 `anyMatch(s -> "xxx".equals(s.signalType()))`，不断言列表长度，新增规则时老用例不会被动挂掉。

---

## 六、遗留事项

1. **`万` 单位**：本次会话中先标记为未实现；会后已补充支持（`1万-3万`、`1.5万-3万`、`8K-3万` 均可识别，统一折算为元后按 >2 倍判定），规则引擎测试增至 14 个。
2. **`salary_mismatch` 稳定性依赖模型**：判断"薪资是否低于市场合理区间"缺少外部基准，建议后续用"岗位要求等级 + 薪资区间"规则辅助校验。
3. **业务线关键词表为启发式**：`BUSINESS_KEYWORDS` 目前 15 个词，覆盖不全时会误报 `vague_duty` / `urgency_noise`，需按真实 JD 逐步扩充。
