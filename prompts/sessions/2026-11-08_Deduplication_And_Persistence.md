# 会话记录：AuditService 落库去重与事务边界优化

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-07
- 主题：合并去重、`@Transactional` 事务控制、LLM 调用移出长事务、故障注入验证回滚
- 涉及文件：
  - `src/main/java/com/example/jobaudit/service/AuditService.java`（新增）
  - `src/main/java/com/example/jobaudit/service/AuditPersistenceService.java`（新增）
  - `src/main/java/com/example/jobaudit/service/AuditSignalRecordService.java`（新增）
  - `src/main/java/com/example/jobaudit/api/JobAuditController.java`（瘦身）
  - `src/test/java/com/example/jobaudit/service/AuditTransactionTest.java`（新增）

---

## 一、需求原文

### 1.1 第一轮：落库编排与去重

> 今天我们要完善数据落库逻辑，重点是"合并去重"与"事务控制"。
> 创建 AuditService（`@Service` + `@Transactional`）……`processAndSaveAudit`……合并去重（RULE 优先；同类 LLM 取 confidence 最高）……`saveBatch`……返回 `AuditResultResponse`……修改 JobAuditController，把存库逻辑全部委托给 AuditService，Controller 只负责接客。

### 1.2 第二轮：事务边界优化 + 故障注入

> 1. 优化事务边界：把 SignalExtractionService 的 LLM 调用和 AuditRuleEngine 的计算，全部移到 `@Transactional` 方法之外。先算出合并去重后的最终列表，然后再进入一个只包含 saveBatch 的短事务。
> 2. 补充故障注入测试：写一个测试用例，Mock 掉 saveBatch 让它抛出异常，验证 job_audit 表也没有新增记录，确保回滚生效。
> 对于第 1 点（响应结构变化），我已知晓，第三周写前端时会按新结构调整。

---

## 二、整体结构：编排与持久化拆分

第一版把"规则计算 + LLM 调用 + 合并去重 + 插入主记录 + 批量插入信号"全部放在一个 `@Transactional` 方法里。问题：

- LLM 调用是秒级网络请求，事务期间一直占用数据库连接；
- 事务范围包含大量纯内存计算，没有意义地拉长事务窗口；
- 若同类的 `@Transactional` 方法被本类内部调用，会绕过 Spring AOP 代理，事务注解失效。

因此拆成两个 Bean：

| 组件 | 职责 | 事务 |
| --- | --- | --- |
| `AuditService` | 规则扫描、LLM 提取、合并去重、组装响应 | 无事务 |
| `AuditPersistenceService` | 插入 `job_audit`、回填主键、`saveBatch` 写 `audit_signal` | `@Transactional` 短事务 |
| `AuditSignalRecordService` | 基于 `ServiceImpl` 暴露可注入、可 Mock 的 `saveBatch` | 随外层事务 |

拆分后解决了自调用代理问题：`AuditService` 调用的是另一个 Bean 的 `@Transactional` 方法，事务代理正常生效。

调用链：

```
JobAuditController#createAudit
  └─ AuditService#processAndSaveAudit        （无事务，纯计算）
       ├─ AuditRuleEngine#analyze            （规则硬匹配）
       ├─ SignalExtractionService#extract    （LLM，秒级）
       ├─ mergeAndDeduplicate                （内存去重）
       └─ AuditPersistenceService#saveAudit  （@Transactional）
            ├─ jobAuditMapper.insert
            ├─ 回填 auditId / createdAt
            └─ signalRecordService.saveBatch(signals, 50)
```

---

## 三、AuditService：只做编排，不碰事务

```java
@Service
public class AuditService {

    public AuditResultResponse processAndSaveAudit(String companyName, String jdText) {
        List<AuditSignalRecord> ruleRecords = ruleEngine.analyze(jdText).stream()
                .map(this::toRuleRecord)
                .toList();
        List<AuditSignalRecord> llmRecords = toLlmRecords(signalExtractionService.extract(jdText));

        List<AuditSignalRecord> merged = mergeAndDeduplicate(ruleRecords, llmRecords);

        JobAudit jobAudit = auditPersistenceService.saveAudit(companyName, jdText, merged);

        log.info("审计完成，auditId={}，规则信号={}，LLM信号={}，合并去重后={}，已批量落库",
                jobAudit.getId(), ruleRecords.size(), llmRecords.size(), merged.size());

        return new AuditResultResponse(jobAudit.getId(), companyName, jdText,
                jobAudit.getStatus(), jobAudit.getCreatedAt(), merged);
    }
}
```

关键点：

- 类上只有 `@Service`，**没有 `@Transactional`**；
- LLM 调用发生在事务开启之前，调用期间不占用数据库连接；
- `auditId`、`createdAt` 不再由编排层设置，改由持久化层在插入主记录后统一回填。

---

## 四、合并去重策略

### 4.1 规则信号 → 实体

```java
private AuditSignalRecord toRuleRecord(AuditSignal signal) {
    AuditSignalRecord record = new AuditSignalRecord();
    record.setSignalType(signal.signalType());
    record.setSeverity(signal.severity());
    record.setReason(signal.reason());
    record.setSource(SOURCE_RULE);
    return record;
}
```

### 4.2 LLM 信号 → 实体

```java
private List<AuditSignalRecord> toLlmRecords(ExtractedSignal signal) {
    if (!"OK".equals(signal.status())
            || signal.signalType() == null
            || NO_SIGNAL.equals(signal.signalType())) {
        log.info("LLM 未产生语义信号，status={}，signalType={}", signal.status(), signal.signalType());
        return List.of();
    }
    double confidence = signal.confidence() == null ? DEFAULT_LLM_CONFIDENCE : signal.confidence();
    AuditSignalRecord record = new AuditSignalRecord();
    record.setSignalType(signal.signalType());
    record.setSeverity(severityOf(confidence));
    record.setReason(signal.reason());
    record.setConfidence(BigDecimal.valueOf(confidence));
    record.setSource(SOURCE_LLM);
    return List.of(record);
}
```

兜底约定：

- LLM 返回 `none` 或解析失败 `pending_review` 时，视为"无语义信号"，返回空列表，不影响规则结果；
- 模型未给 `confidence` 时补默认值 `0.8`；
- 模型不给 `severity`，按置信度映射：`>=0.8` → `HIGH`，`>=0.5` → `MEDIUM`，其余 `LOW`。

### 4.3 按 signalType 去重

```java
private List<AuditSignalRecord> mergeAndDeduplicate(List<AuditSignalRecord> ruleRecords,
                                                    List<AuditSignalRecord> llmRecords) {
    return Stream.concat(ruleRecords.stream(), llmRecords.stream())
            .filter(record -> record.getSignalType() != null)
            .collect(Collectors.toMap(
                    AuditSignalRecord::getSignalType,
                    Function.identity(),
                    this::preferSignal,
                    LinkedHashMap::new))
            .values()
            .stream()
            .toList();
}

/** 冲突裁决：规则优先于 LLM；同来源时保留置信度更高的一个 */
private AuditSignalRecord preferSignal(AuditSignalRecord existing, AuditSignalRecord candidate) {
    boolean existingIsRule = SOURCE_RULE.equals(existing.getSource());
    boolean candidateIsRule = SOURCE_RULE.equals(candidate.getSource());
    if (existingIsRule != candidateIsRule) {
        return existingIsRule ? existing : candidate;
    }
    return confidenceOf(candidate).compareTo(confidenceOf(existing)) > 0 ? candidate : existing;
}
```

规则：

1. 同一个 `signalType` 同时被规则引擎和 LLM 命中，保留 `RULE` —— 规则是确定性判断，不依赖模型输出；
2. 同来源冲突（目前主要是多条 LLM 信号）保留 `confidence` 更高者；
3. 规则信号没有 `confidence`，比较时按 `BigDecimal.ZERO` 处理；
4. 使用 `LinkedHashMap` 保留插入顺序，规则信号在前、LLM 信号在后，响应顺序稳定。

---

## 五、短事务：只包含数据库写入

```java
@Service
public class AuditPersistenceService {

    private static final int BATCH_SIZE = 50;
    private static final String STATUS_SCANNED = "SCANNED";

    @Transactional
    public JobAudit saveAudit(String companyName, String jdText, List<AuditSignalRecord> signals) {
        JobAudit jobAudit = new JobAudit();
        jobAudit.setCompanyName(companyName);
        jobAudit.setInputText(jdText);
        jobAudit.setStatus(STATUS_SCANNED);
        jobAudit.setCreatedAt(LocalDateTime.now());
        jobAuditMapper.insert(jobAudit);

        Long auditId = jobAudit.getId();
        LocalDateTime signalCreatedAt = LocalDateTime.now();
        for (AuditSignalRecord signal : signals) {
            signal.setAuditId(auditId);
            signal.setCreatedAt(signalCreatedAt);
        }
        if (!signals.isEmpty()) {
            signalRecordService.saveBatch(signals, BATCH_SIZE);
        }
        return jobAudit;
    }
}
```

要点：

- 事务内只剩"插入主记录 + 回填外键 + 批量写入"，毫秒级；
- `job_audit` 的 `id` 自增回填后，作为 `audit_signal.audit_id` 外键；
- `saveBatch` 按 50 条分片，避免单条 SQL 过长；
- 任一条写入失败，`job_audit` 与已写入的信号一起回滚。

### 5.1 为什么要单独抽 AuditSignalRecordService

原先使用 MyBatis-Plus 的静态工具 `Db.saveBatch(...)`，静态调用无法被 Mockito 替换，故障注入测试无从下手。改为：

```java
@Service
public class AuditSignalRecordService extends ServiceImpl<AuditSignalRecordMapper, AuditSignalRecord> {
}
```

继承 `ServiceImpl` 后直接获得 `saveBatch`，既能被 Spring 注入到持久化层，也能在测试中用 `@MockitoBean` 替换并指定抛异常。

---

## 六、Controller 只负责接客

`JobAuditController` 已瘦身到 65 行，POST 不再自己拼装实体、不再自己调用 `saveBatch`：

```java
AuditResultResponse response = auditService.processAndSaveAudit(request.companyName(), request.inputText());
```

GET `/api/audits/{id}` 仍保留 `JobAuditMapper` 查询，抛出 `AuditNotFoundException` 由全局异常处理转 404。

---

## 七、故障注入测试：验证回滚

```java
@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key")
class AuditTransactionTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private JobAuditMapper jobAuditMapper;

    @MockitoBean
    private AuditSignalRecordService signalRecordService;

    /** 避免测试真实调用 LLM，固定返回一条语义信号 */
    @MockitoBean
    private SignalExtractionService signalExtractionService;

    @Test
    void shouldRollbackJobAuditWhenBatchInsertFails() {
        when(signalExtractionService.extract(anyString()))
                .thenReturn(ExtractedSignal.ok("pua_culture", "测试用语义信号", 0.9, "{}"));
        when(signalRecordService.saveBatch(anyList(), anyInt()))
                .thenThrow(new RuntimeException("模拟批量写入失败"));

        long before = jobAuditMapper.selectCount(null);
        String jdText = "岗位职责：要求精通Java、前端、运维、算法，薪资4-6K，单休，能吃苦抗压，领导交办的其他工作。";

        assertThrows(RuntimeException.class,
                () -> auditService.processAndSaveAudit("回滚验证公司", jdText));

        long after = jobAuditMapper.selectCount(null);
        assertEquals(before, after, "批量写入失败后 job_audit 不应残留新记录");
    }
}
```

设计说明：

- 用 `@MockitoBean`（Spring Boot 3.5）替换两个 Bean：LLM 提取返回固定信号，`saveBatch` 抛异常；
- 断言方式是 `job_audit` 的 `selectCount(null)` 前后相等 —— 如果事务没有回滚，`insert` 已提交，count 会 +1；
- 测试连接真实 MySQL（`job_audit_db`），Flyway 在上下文启动时校验迁移，因此验证的是真实数据库回滚行为，而不是内存模拟；
- 类上补 `properties = "langchain4j.open-ai.chat-model.api-key=test-key"`：不加时 Spring 上下文启动会因为 `${LLM_API_KEY}` 无法解析而失败（即使 ChatModel 在测试里根本没被调用），加上后测试无需设置环境变量即可独立运行。

---

## 八、验证结果

测试命令：

```powershell
mvn test -Dtest=AuditTransactionTest
```

输出：

```
[INFO] Running com.example.jobaudit.service.AuditTransactionTest
...
2026-11-07T14:19:21.001+08:00 INFO c.e.j.service.AuditTransactionTest : Started AuditTransactionTest in 11.826 seconds
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 13.12 s
[INFO] Results:
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

同时确认：

- 全项目 `@Transactional` 只出现在 `AuditPersistenceService` 一处；
- 主代码中已无 `Db.saveBatch` 静态调用；
- `AuditService` 类上无事务注解，LLM 调用与规则计算均在事务外。

---

## 九、设计权衡与遗留事项

1. **LLM 已调用但写库失败**：数据一致性有保证（全部回滚），但模型调用成本已经发生。这是把秒级调用移出事务的必然代价，换来的是数据库连接占用从"模型耗时"降到"写入耗时"。
2. **响应结构变化**：POST 现在统一返回 `AuditResultResponse`（`auditId`、`companyName`、`inputText`、`status`、`createdAt`、`signals`），前端第三周按新结构适配。
3. **回滚验证只覆盖 `saveBatch` 失败路径**：`jobAuditMapper.insert` 自身失败的回滚由同一事务语义保证，未单独写用例。
4. **测试依赖本地 MySQL**：`@SpringBootTest` 使用真实数据源，CI 环境需要可用的 `job_audit_db` 与 Flyway 迁移。
