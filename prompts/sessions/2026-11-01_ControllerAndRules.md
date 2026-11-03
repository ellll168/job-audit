# 会话记录：规则引擎补全 + Controller 闭环 + TraceId 日志

- 项目：job-audit（岗位反向尽调助手）
- 会话日期：2026-11-03
- 涉及文件：
  - `src/main/java/com/example/jobaudit/rule/AuditRuleEngine.java`（修改）
  - `src/main/java/com/example/jobaudit/api/JobAuditController.java`（新建）
  - `src/main/java/com/example/jobaudit/domain/JobAudit.java`（新建）
  - `src/main/java/com/example/jobaudit/domain/AuditRequest.java`（新建）
  - `src/main/java/com/example/jobaudit/domain/AuditResponse.java`（新建）
  - `src/main/java/com/example/jobaudit/domain/AuditSignalRecord.java`（新建）
  - `src/main/java/com/example/jobaudit/mapper/JobAuditMapper.java`（新建）
  - `src/main/java/com/example/jobaudit/mapper/AuditSignalRecordMapper.java`（新建）
  - `src/main/resources/application.properties`（修改）

---

## 第 1 轮：补全 4 条规则 + 创建 JobAuditController

### 用户提问

> @.trae-context.md 今天我们要补全规则引擎，并搭建 API 闭环。请按以下要求写代码：
>
> 任务一：补全 AuditRuleEngine 剩下的 4 条规则（写在 analyze 方法里）：
>
> - 要求矛盾：JD 中同时出现"资深/专家"和"应届/小白/无经验"，输出 signalType="contradictory_req", severity="MEDIUM"。
> - 紧迫话术：出现"急招/火速/urgent"但没写明具体的业务线或项目，输出 signalType="urgency_noise", severity="LOW"。
> - 招转培危险词：出现"培训费/押金/先培训后上岗/包就业"，输出 signalType="scam_risk", severity="HIGH"。
> - 超长待机：JD 文本中出现"抗压/能吃苦"且工作制描述为"单休/大小周"，输出 signalType="overwork_risk", severity="MEDIUM"。
>
> 任务二：创建 JobAuditController (在 api 包下)：
>
> - 提供一个 POST /api/audits 接口，接收 JSON 参数 {"companyName": "...", "inputText": "..."}。
> - 在接口入口打印 SLF4J 日志，要求必须包含 traceId（可以用 UUID.randomUUID() 简单生成，并用 MDC.put 放入上下文，方便以后排查）。
> - 在 Controller 中，简单地把输入存入 job_audit 表（可以先用 MyBatis-Plus 的 BaseMapper 试写），然后调用 AuditRuleEngine.analyze() 跑一遍规则，最后将结果返回给前端。
> - 加上 @Validated 和基本的参数非空校验（@NotBlank）。

### 处理过程

1. 先检查 `pom.xml`：`spring-boot-starter-validation` 与 `mybatis-plus-spring-boot3-starter` 均已存在，无需加依赖。
2. 规则引擎补全（`analyze` 共执行 6 条规则）：

| 规则 | signalType | severity | 触发条件 |
|---|---|---|---|
| 要求矛盾 | contradictory_req | MEDIUM | `资深\|专家` 与 `应届\|小白\|无经验` 同时出现 |
| 紧迫话术 | urgency_noise | LOW | `急招\|火速\|urgent`（忽略大小写）且无业务线/项目描述 |
| 招转培危险词 | scam_risk | HIGH | 命中 `培训费\|押金\|先培训后上岗\|包就业`，reason 带出实际命中词 |
| 超长待机 | overwork_risk | MEDIUM | `抗压\|能吃苦` 且 `单休\|大小周` 同时出现 |

3. "无具体业务线/项目"抽成公共方法 `hasConcreteBusiness()`，规则 2、4 复用。
4. Controller 支撑类：`JobAudit` 实体（手写 getter/setter，无 Lombok，MP 驼峰转下划线自动映射）、`AuditRequest` / `AuditResponse` record DTO、`JobAuditMapper`（`@Mapper` + `BaseMapper`）。
5. TraceId 方案：`MDC.put("traceId", UUID)`，在 `finally` 中 `MDC.remove`（防止 Tomcat 线程池复用导致 traceId 串号）。

### 完整代码

`AuditRuleEngine.java` 新增部分（规则 3~6 及公共方法）：

```java
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
```

`JobAuditController.java`（初版，第 2 轮又追加了信号落库）：

```java
package com.example.jobaudit.api;

import com.example.jobaudit.domain.AuditRequest;
import com.example.jobaudit.domain.AuditResponse;
import com.example.jobaudit.domain.AuditSignal;
import com.example.jobaudit.domain.JobAudit;
import com.example.jobaudit.mapper.JobAuditMapper;
import com.example.jobaudit.rule.AuditRuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audits")
@Validated
public class JobAuditController {

    private static final Logger log = LoggerFactory.getLogger(JobAuditController.class);

    private final JobAuditMapper jobAuditMapper;
    private final AuditRuleEngine ruleEngine;

    public JobAuditController(JobAuditMapper jobAuditMapper, AuditRuleEngine ruleEngine) {
        this.jobAuditMapper = jobAuditMapper;
        this.ruleEngine = ruleEngine;
    }

    @PostMapping
    public AuditResponse create(@Validated @RequestBody AuditRequest request) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        try {
            log.info("收到岗位审计请求，companyName={}", request.companyName());

            JobAudit jobAudit = new JobAudit();
            jobAudit.setCompanyName(request.companyName());
            jobAudit.setInputText(request.inputText());
            jobAudit.setStatus("SCANNED");
            jobAudit.setCreatedAt(LocalDateTime.now());
            jobAuditMapper.insert(jobAudit);

            List<AuditSignal> signals = ruleEngine.analyze(request.inputText());
            log.info("规则扫描完成，auditId={}，命中信号数={}", jobAudit.getId(), signals.size());

            return new AuditResponse(jobAudit.getId(), jobAudit.getStatus(), signals);
        } finally {
            MDC.remove("traceId");
        }
    }
}
```

支撑类（`JobAudit` 实体 / `AuditRequest` / `AuditResponse` / `JobAuditMapper`）要点：

```java
// JobAudit.java（domain 包）：@TableName("job_audit")，@TableId(type = IdType.AUTO)，
// 字段 inputText/companyName/status/createdAt，手写 getter/setter

// AuditRequest.java（domain 包）：
public record AuditRequest(
        @NotBlank(message = "companyName 不能为空") String companyName,
        @NotBlank(message = "inputText 不能为空") String inputText) {
}

// AuditResponse.java（domain 包）：
public record AuditResponse(Long auditId, String status, List<AuditSignal> signals) {
}

// JobAuditMapper.java（mapper 包）：
@Mapper
public interface JobAuditMapper extends BaseMapper<JobAudit> {
}
```

### 故障排查：Public Key Retrieval is not allowed

首次跑测试上下文启动失败，表面报错是 `jobAuditMapper` 找不到 `sqlSessionTemplate`，沿 `Caused by` 链定位到真实根因：

```
Caused by: java.sql.SQLNonTransientConnectionException: Public Key Retrieval is not allowed
```

MySQL 9 的 `caching_sha2_password` 认证在非 SSL 连接下要求 RSA 公钥。修复：`application.properties` 的 JDBC URL 追加 `allowPublicKeyRetrieval=true`（本地开发可接受，生产建议改用 SSL）。

### 验证结果

- `mvn test` 全绿（含前一日规则回归测试）
- 真实启动应用，POST 一条"五毒俱全"的 JD → `auditId=1, status=SCANNED`，5 条信号全部命中
- 空 `companyName` 请求 → 400（校验生效）

---

## 第 2 轮：AuditSignalRecord 实体 + 信号落库

### 用户提问

> 1. 请生成 audit_signal 表对应的实体类 AuditSignalRecord（放在 domain 包下，遵循 .trae-context.md 的规范）。
> 2. 生成对应的 AuditSignalRecordMapper 继承 MyBatis-Plus 的 BaseMapper。
> 3. 修改刚才的 JobAuditController：在调用 AuditRuleEngine.analyze() 得到 List&lt;AuditSignal&gt; 之后，把结果转换成 AuditSignalRecord，通过 Mapper 存入数据库，然后再返回给前端。

### 处理过程

1. `AuditSignalRecord` 实体：`@TableName("audit_signal")`，8 个字段与 `V1__init.sql` 一一对应（`confidence` DECIMAL → `BigDecimal`），手写 getter/setter。
2. `AuditSignalRecordMapper`：`@Mapper` + `extends BaseMapper<AuditSignalRecord>`。
3. Controller 改造：`analyze()` 之后通过私有方法 `toRecord()` 做 `AuditSignal → AuditSignalRecord` 转换并逐条 insert。约定：
   - `auditId` 取自先入库的 `job_audit` 主键（MP 自增回填），保证外键关联；
   - `source` 固定 `"RULE"`（与将来的 `"LLM"` 区分）；规则信号无置信度，`confidence` 留 null。

Controller 落库部分代码：

```java
            List<AuditSignal> signals = ruleEngine.analyze(request.inputText());

            List<AuditSignalRecord> records = signals.stream()
                    .map(s -> toRecord(s, jobAudit.getId()))
                    .toList();
            records.forEach(signalRecordMapper::insert);
            log.info("规则扫描完成，auditId={}，命中信号数={}，已全部落库", jobAudit.getId(), records.size());

            return new AuditResponse(jobAudit.getId(), jobAudit.getStatus(), signals);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** 规则引擎输出 -> audit_signal 表实体；规则信号无置信度评分，source 固定为 RULE */
    private AuditSignalRecord toRecord(AuditSignal signal, Long auditId) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setAuditId(auditId);
        record.setSignalType(signal.signalType());
        record.setSeverity(signal.severity());
        record.setReason(signal.reason());
        record.setSource("RULE");
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
```

### 验证结果

真实启动应用 → POST → 直接查库确认：

```
audit_signal 表：5 行，audit_id=2，source=RULE
  salary_opacity/HIGH、contradictory_req/MEDIUM、urgency_noise/LOW、
  scam_risk/HIGH、overwork_risk/MEDIUM
job_audit 表：id=2（某培训机构，SCANNED）
```

注：`records.forEach(insert)` 为逐条插入，当前信号量级足够；若将来单次扫描信号量大，可换 MyBatis-Plus `IService.saveBatch` 真批量。

## 遗留事项

- MDC 的 traceId 要在日志中可见，需在 `application.properties` 配置日志格式，例如：`logging.pattern.level=%5p [%X{traceId}]`
