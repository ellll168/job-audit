# 会话记录：AI 三视角输出（锐评 / 锦囊 / 追问）与暗黑视觉收尾

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-11 ~ 2026-11-12（比赛最终交付阶段）
- 涉及文件：
  - `src/main/java/com/example/jobaudit/domain/AuditResultResponse.java`
  - `src/main/java/com/example/jobaudit/service/AuditService.java`
  - `src/main/java/com/example/jobaudit/service/JdSignalCache.java`
  - `src/main/java/com/example/jobaudit/api/JobAuditController.java`
  - `frontend/src/App.vue`
  - `README.md`

---

## 一、用户需求（原文摘要）

1. 给当前接口新增字段 `List<String> avoidanceTips`，在 `AuditService` 里用一次轻量模型调用生成，**不落库**，通过 GET 接口返回。
2. 提示词要求：AI 扮演资深职场导师，针对 JD 具体坑点生成 2-3 条极其直接、一针见血的“避坑建议”（每条不超过 30 字）。
3. 前端在“毒舌锐评”下方加一张极其醒目的卡片：`🛡️ 你的专属避坑锦囊`，微黄警示边框 + 暗黑毛玻璃，逐条以 `👉` 加粗渲染，无数据时不显示。
4. 后续追加：

> 在调用大模型时，请严格约束三个字段的输出格式，绝对不允许出现内容重叠：
> 1. aiVerdict：极其简短（30 字内），用犀利、幽默的吐槽口吻，一句话总结这个岗位的最大致命伤。
> 2. avoidanceTips：生成 2-3 条陈述性的防御建议，直接告诉求职者心里该防什么（如警惕底薪、拒绝白嫖）。
> 3. questions：生成 3 个疑问句，模拟求职者直接向 HR 提问的口吻，用于面试实战试探。

5. 最后追加：`把 questions 里连续问句拆成单行`。

---

## 二、核心问题：三个字段为什么会“内容重叠”

三个字段都由同一个模型、同一段 JD + 同一批 risk signals 生成，如果不做视角区分，模型的默认行为是：

| 现象 | 实际观测 |
|---|---|
| 锐评变成建议 | “薪资面议单休，建议你面试问清底薪”——一句话里既吐槽又给了动作 |
| 锦囊变成提问清单 | 原提示词写的是“问什么、确认什么、要什么证明”，产出的其实和 questions 同质 |
| 追问变成第三人称分析 | “该岗位薪资结构不透明，建议了解薪资构成”，不是求职者口吻 |

根因是**三者的“说话人视角”没有被显式指定**。修正思路不是加更多形容词，而是给每个字段定义唯一的**言语行为（speech act）**：

- `aiVerdict` → **吐槽/定性**（我不给方案，我只下判断）
- `avoidanceTips` → **防御/陈述**（我不问，我只提醒你该防什么）
- `questions` → **进攻/疑问**（我不判断，我只向 HR 发问）

---

## 三、Prompt 设计（最终版）

### 3.1 aiVerdict：吐槽视角，只下判断

```java
private static final SystemMessage VERDICT_SYSTEM_PROMPT = SystemMessage.from("""
        你是一个嘴上不饶人但句句在理的求职风控毒舌评论员。
        任务：根据招聘 JD 原文与检测到的风险信号，用一句话吐槽这个岗位最大的致命伤，不超过 30 字。
        要求：
        1. 语气犀利、幽默、带讽刺，但必须基于已检测到的风险，不得编造；
        2. 只做“吐槽定性”，绝对不要给建议、不要给问句、不要列举多条；
        3. 示例：“要求全栈大牛却只给 4-6K，还大谈狼性文化”“薪资面议单休，这哪是招人是找牛马”。
        只输出这一句话本身，不要引号、不要解释、不要换行。""");
```

约束要点：

- **任务词单一化**：只出现“吐槽”“总结致命伤”，不出现“建议”“如何应对”
- **负向约束显式化**：`绝对不要给建议、不要给问句、不要列举多条`
- **示例即边界**：给的两个例句都是纯判断句，没有一个动词指向“你该去做什么”

### 3.2 avoidanceTips：防御视角，只给陈述

```java
private static final SystemMessage AVOIDANCE_SYSTEM_PROMPT = SystemMessage.from("""
        你是一位说话极其直接、一针见血的资深职场导师。
        任务：根据招聘 JD 原文与检测到的风险点，给求职者 2-3 条“心里要防什么”的防御建议。
        要求：
        1. 每条不超过 30 字，写成陈述句（判断或提醒），例如“底薪不写清楚，综合月薪就是画大饼”、
           “单休还没补贴，本质是白嫖你的周末”、“职责写得越模糊，进去后杂活越多”；
        2. 必须是结论式的提醒，绝对不要写成问句，也不要写成“去问 HR 什么”的提问清单；
        3. 不要复述吐槽、不要写感慨，每条只讲一个要防的点；
        4. 只输出 2-3 行，每行一条，不要编号、不要解释、不要任何多余文字。""");
```

关键修正：旧版提示词写的是「面试时可执行的动作（问什么、确认什么、要什么证明）」，这一句直接把锦囊推向了 questions 的语义区间。最终版改为「**心里要防什么**」，并显式禁止“去问 HR 什么”的提问清单。

示例全部改写为陈述句式（`X 就是 Y`），从示例层面就把问句排除了。

### 3.3 questions：进攻视角，只发问

```java
private static final SystemMessage QUESTION_SYSTEM_PROMPT = SystemMessage.from("""
        你是一个正在面试现场、准备向 HR 提问的求职者。
        任务：结合招聘 JD 原文与检测到的风险点，写出 3 个你当场想问 HR 的问题。
        要求：
        1. 每一句都必须是疑问句，以“？”结尾，口吻是应聘者直接向 HR 提问，不是第三人称分析；
        2. 直击痛点、语气礼貌但不退让，尽量引用 JD 细节（薪资数字、技能要求、工作制度）；
        3. 只输出问题，绝对不要写建议、不要写陈述句、不要写吐槽；
        4. 示例：“这个岗位的底薪具体是多少？综合薪资的构成能详细说下吗？”
        只输出 3 行，每行一个问题，不要编号、不要解释、不要任何多余文字。""");
```

角色设定从「资深职场专家」改为「**正在面试现场、准备提问的求职者**」——人称一变，输出自然从分析视角切到第一人称提问。

### 3.4 三视角边界对照

| 维度 | aiVerdict | avoidanceTips | questions |
|---|---|---|---|
| 言语行为 | 吐槽 / 定性 | 防御 / 陈述 | 进攻 / 疑问 |
| 说话人 | 毒舌评论员 | 职场导师（对你） | 求职者（对 HR） |
| 人称 | 第三人称 | 第二人称隐含（你该防） | 第一人称（我想问） |
| 句末形态 | 句号或直接结束 | 陈述句 | 必须是 `？` |
| 长度 | ≤ 30 字，一句 | 2-3 条，每条 ≤ 30 字 | 3 条，每条单句 |
| 禁止内容 | 建议、问句、多条并列 | 问句、提问清单、复述吐槽 | 建议、陈述、吐槽 |

---

## 四、代码层兜底（不只靠模型自觉）

Prompt 是软约束，因此三处解析都加了硬边界。

### 4.1 锐评硬截断 30 字

```java
/** 毒舌锐评的长度硬上限，超出则截断 */
private static final int VERDICT_MAX_LENGTH = 30;
```

`sanitizeVerdict()` 在去掉引号与换行后按该常量截断，模型超写也不会溢出卡片。

### 4.2 锦囊过滤问句

```java
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
```

即使模型把某条写成了问句，这一行也不会进入锦囊，而是被丢弃（问句归 questions）。

### 4.3 追问拆句 + 统一问号

```java
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
```

三点设计：

1. 按 `(?<=[？?])` 零宽切分，保留问号本身，一行两个问句 → 两条
2. 只保留以问号结尾的片段，混在后面的陈述句（如“请告知”）被剔除
3. 整行没有问号时兜底补 `？`，避免过度过滤导致空结果

---

## 五、avoidanceTips 全链路（不落库）

### 5.1 响应体

```java
public record AuditResultResponse(
        Long auditId,
        String companyName,
        String inputText,
        String status,
        LocalDateTime createdAt,
        List<AuditSignalRecord> signals,
        String aiVerdict,
        List<String> avoidanceTips) {
}
```

### 5.2 与锐评同批生成，随缓存复用

`JdSignalCache.CachedAudit` 由 `(signals, aiVerdict)` 扩展为三元素：

```java
public record CachedAudit(List<AuditSignalRecord> signals, String aiVerdict, List<String> avoidanceTips) {
}
```

`processAndSaveAudit()` 在缓存未命中时一次生成锐评 + 锦囊，并写回缓存；命中时两者一起复用，**24 小时内同一段 JD 零模型调用**。降级路径（`pending_review`）依旧不写缓存，避免 AI 恢复后 24 小时仍返回空锦囊。

### 5.3 GET 按需生成

```java
public List<String> resolveAvoidanceTips(String jdText, List<AuditSignalRecord> signals) {
    List<String> cachedTips = jdSignalCache.get(jdText)
            .map(JdSignalCache.CachedAudit::avoidanceTips)
            .orElse(null);
    if (cachedTips != null) {
        return cachedTips;
    }
    return generateAvoidanceTips(jdText, withoutSystemSignals(signals));
}
```

GET 场景先查缓存，未命中才生成，且**不回写缓存**——此处信号来自数据库，可能包含 `SYSTEM` 来源的 `AI_SERVICE_DOWN` 降级提示，写回去会污染语义提取的缓存内容。

### 5.4 降级策略

| 场景 | 行为 |
|---|---|
| `jobaudit.ai.enabled=false`（demo 模式） | 直接返回空列表，不外呼 |
| 无风险信号 | 直接返回空列表，不浪费一次调用 |
| 模型异常 | `try/catch` 返回空列表，不阻断审计主流程 |
| 落库 | **不落库**，无新增列、无 Flyway 迁移 |

---

## 六、前端：避坑锦囊卡片

位置：紧跟 `<header>`（header 内含毒舌锐评）之后，整幅宽度，避免挤在左栏窄列里。

```vue
<section
  v-if="audit?.avoidanceTips?.length"
  class="mt-6 bg-slate-900/60 backdrop-blur-md border-2 border-amber-400/60 rounded-2xl
         shadow-[0_0_30px_rgba(251,191,36,0.20)] p-6"
>
  <h2 class="flex items-center gap-2 text-lg font-bold text-amber-300 mb-4 tracking-wide">
    🛡️ 你的专属避坑锦囊
  </h2>
  <ul class="space-y-3">
    <li
      v-for="(tip, i) in audit.avoidanceTips"
      :key="i"
      class="flex items-start gap-2.5 text-base font-bold text-amber-100 leading-relaxed"
    >
      <span class="select-none">👉</span>
      <span>{{ tip }}</span>
    </li>
  </ul>
</section>
```

设计说明：

- `border-2 border-amber-400/60`：微黄警示边框（2px 比常见的 1px 更“突兀”，符合“极其醒目”）
- `bg-slate-900/60 backdrop-blur-md`：暗黑毛玻璃，与页面其它卡片同一材质语言
- `shadow-[0_0_30px_rgba(251,191,36,0.20)]`：琥珀色外发光，让卡片从深色背景中浮起来
- `v-if="audit?.avoidanceTips?.length"`：无数据时整块不渲染（含标题），不留空壳
- `TypeScript` 侧同步补 `avoidanceTips: string[]`，避免 `vue-tsc` 报错

---

## 七、暗黑视觉风（扫描线 / 警戒线 / 发光边框）

### 7.1 终端扫描线

```css
/* ===== 终端扫描线（极慢向下漂移，制造 CRT 感） ===== */
.da-scanlines {
  background-image: repeating-linear-gradient(
    to bottom,
    rgba(148, 163, 184, 0.05) 0px,
    rgba(148, 163, 184, 0.05) 1px,
    transparent 1px,
    transparent 4px
  );
  animation: da-scanline-drift 8s linear infinite;
}

@keyframes da-scanline-drift {
  from { background-position-y: 0; }
  to   { background-position-y: 24px; }
}
```

挂在根容器的绝对定位层上（`<div class="absolute inset-0 da-scanlines" />`），4px 周期里只亮 1px，透明度 0.05，保证是“若有若无的肌理”而不是干扰阅读的扫描条。

### 7.2 HIGH 风险卡片黄黑警戒线

```css
.da-hazard { position: relative; overflow: hidden; }

.da-hazard::before {
  content: '';
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 6px;
  background-image: repeating-linear-gradient(
    135deg,
    #fbbf24 0px, #fbbf24 6px,
    #111827 6px, #111827 12px
  );
  box-shadow: 0 0 10px rgba(251, 191, 36, 0.25);
}
```

用伪元素画 45° 黄黑斜条，不额外增加 DOM；`box-shadow` 让警戒线自身发光，强化“高危”语义。

### 7.3 发光边框的层次

| 元素 | 手法 |
|---|---|
| 毒舌锐评 | `border-red-500/30` + `shadow-[0_0_20px_rgba(239,68,68,0.10)]` + 手写角标 `[ WARNING: AI VERDICT ]` |
| 避坑锦囊 | `border-amber-400/60` + `shadow-[0_0_30px_rgba(251,191,36,0.20)]`（比锐评更亮，强调可执行） |
| 普通卡片 | `border-slate-700/50` + `shadow-2xl`，只做毛玻璃不做彩色光 |

逻辑：**红=警报、黄=行动、灰=信息**，颜色不是装饰而是风险等级编码。

### 7.4 无障碍

```css
@media (prefers-reduced-motion: reduce) {
  .da-scanlines { animation: none; }
}
```

系统开启“减少动效”时停止扫描线漂移，避免视觉敏感用户不适。

---

## 八、验证记录

### 8.1 三字段格式（真实 DeepSeek 调用）

```
aiVerdict (27 字)
  薪资面议单休还要求精通全栈，这哪是招工程师是找全能牛马

avoidanceTips (3 条，17/19/23 字，全为陈述句)
  薪资面议加急招，定价权全在对方手里
  单休还写能吃苦，加班就是默认的入职门槛
  要求精通四个方向，进去就是一个人干一个团队的活

questions (3 条，每条仅 1 个问号)
  这个岗位的薪资“面议”具体范围是多少，能否给出明确的薪资区间和构成？
  JD 要求“精通前端、后端、运维、算法”，请问岗位职责边界如何划分，具体业务线是什么？
  JD 写明“单休”和“接受加班”，请问日常加班频率和调休或加班补偿机制具体是怎样的？
```

三块职责清晰：**锐评下判断**（招人还是找牛马）→ **锦囊讲该防什么**（定价权不在你手里）→ **反问去问什么**（区间是多少），没有交叉重复。

### 8.2 拆句前后对比

| | 拆句前 | 拆句后 |
|---|---|---|
| 第 1 条 | 「…具体是多少？…能透露一下吗？」两个问号挤在一行 | 只保留首句，第二个问句顺延补位 |

`limit(QUESTION_COUNT = 3)` 仍生效，拆句不会让返回列表变长。

### 8.3 前端

- `npm run build`（`vue-tsc` + vite）通过
- 浏览器实测 `?auditId=2` 恢复态：卡片标题、黄色边框、三条 `👉` 加粗建议全部正常渲染，与后端返回完全一致
- 空数据场景（该 JD 无任何风险信号）卡片整块不出现，符合需求

### 8.4 后端测试

```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

H2 内存库 + Mock 模型，无 MySQL、无 API Key 亦可全绿。

---

## 九、踩坑与取舍

1. **缓存三元素化而非新增独立缓存**：锦囊与锐评同源（JD + signals），放在同一个 Caffeine entry 里语义一致，也避免两个维护两套 TTL 的缓存。
2. **降级不写缓存**：AI 挂掉时锐评为 `null`、锦囊为空。若写进缓存，24 小时内即使 AI 恢复也拿不到内容，用户体验是“永久坏了”。
3. **GET 不回写缓存**：GET 读出的 signals 含 `SYSTEM` 来源记录，写回会污染后续语义提取的输入；宁可多算一次，也不让缓存脏掉。
4. **锦囊不落库**：无 `avoidance_tips` 列、无 V3 迁移。代价是进程重启后首次 GET 会重新生成一次，这个代价可接受。
5. **只靠 Prompt 不够**：模型仍会偶尔越界（把锦囊写成问句、把锐评写成建议），所以解析层必须加"问句过滤 / 问号归一 / 长度截断"三道硬闸。
6. **演示模式必须关闭外呼**：否则 `aiEnabled=true` + 占位 Key 会真的去打 DeepSeek，评委要等 10-30 秒才看到降级提示。
