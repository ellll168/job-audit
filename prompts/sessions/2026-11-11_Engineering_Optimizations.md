# 会话记录：工程优化三件事（Caffeine 缓存 / Knife4j 文档 / H2 Demo 模式）

- 项目：job-audit（岗位反向尽调助手）
- 涉及文件：
  - `pom.xml`
  - `src/main/java/com/example/jobaudit/service/JdSignalCache.java`
  - `src/main/java/com/example/jobaudit/service/AuditService.java`
  - `src/main/java/com/example/jobaudit/config/Knife4jConfig.java`
  - `src/main/resources/application.properties`
  - `src/main/resources/application-demo.properties`
  - `src/main/resources/db/demo/schema-h2.sql`
  - `start-demo.bat` / `start-demo.sh`

---

## 一、需求原文

1. 引入 Caffeine 缓存：

> 为了防止同一段 JD 被重复调用大模型（省钱且提效），请帮我引入 Caffeine 缓存。以 JD 文本的 Hash 值为 Key，缓存解析出来的 signals 列表。如果 24 小时内查过一模一样的 JD，直接从内存返回结果。

2. 追加：

> 把 aiVerdict 也纳入缓存，彻底避免重复调用模型。

3. 引入接口文档（Knife4j / OpenAPI）。

4. H2 一键演示：

> 新建一个 application-demo.properties，配置 H2 内存数据库（jdbc:h2:mem:jobaudit），并配置 H2 兼容 MySQL 模式……编写 start-demo.bat / start-demo.sh，评委只需双击即可，不需要安装任何数据库。

---

## 二、Caffeine 本地缓存

### 2.1 设计目标

| 目标 | 落地方式 |
|---|---|
| 相同 JD 24 小时内不重复调用模型 | `expireAfterWrite(24h)` |
| Key 与 JD 文本一一对应 | 空白归一化后取 SHA-256 摘要 |
| 内存不无限增长 | `maximumSize(2000)` + LRU 淘汰 |
| 缓存内容不被持久化污染 | 存取一律返回**副本** |

### 2.2 核心实现

```java
@Component
public class JdSignalCache {

    /** 与产品口径一致：24 小时 */
    private static final Duration TTL = Duration.ofHours(24);

    /** 上限防止内存无限增长，超出按 LRU 淘汰 */
    private static final long MAX_SIZE = 2_000;

    private final Cache<String, CachedAudit> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_SIZE)
            .recordStats()
            .build();

    /** 一次 JD 解析的完整产物：信号列表 + 毒舌锐评 + 避坑建议 */
    public record CachedAudit(List<AuditSignalRecord> signals, String aiVerdict, List<String> avoidanceTips) {
    }
}
```

Key 的生成：

```java
private String keyOf(String jdText) {
    String normalized = jdText.strip().replaceAll("\\s+", " ");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
}
```

两个细节：

1. **先归一化再哈希**：`strip()` + 把连续空白折叠成单个空格，让“仅换行/缩进不同”的同一段 JD 也能命中，而不是必须字节完全一致。
2. **不存原文明文**：缓存 Key 只保留 64 位十六进制摘要，日志里也不会打印 JD 原文（JD 属用户输入，与“关闭 LLM 请求响应日志”是同一条隐私原则）。

### 2.3 副本隔离（这里踩过一个坑）

第一版直接把 `List<AuditSignalRecord>` 放进缓存，结果第二次命中时，信号记录的 `auditId` 是**上一次落库的 ID**——因为 MyBatis-Plus 在批量插入后会回填主键与 auditId，缓存的正是同一批对象引用。

修正：`get()` / `put()` 都做浅拷贝，只复制业务字段，`id / auditId / createdAt` 交给持久化层重新回填。

```java
private AuditSignalRecord copy(AuditSignalRecord source) {
    AuditSignalRecord record = new AuditSignalRecord();
    record.setSignalType(source.getSignalType());
    record.setSeverity(source.getSeverity());
    record.setReason(source.getReason());
    record.setConfidence(source.getConfidence());
    record.setSource(source.getSource());
    return record;
}
```

### 2.4 缓存接入主流程

`AuditService.processAndSaveAudit()` 的顺序：

```
1. 先查缓存（key = SHA-256(JD)）
   ├─ 命中 → 复用 signals + aiVerdict + avoidanceTips，零模型调用
   │         └─ 若 aiVerdict 为空（历史数据/曾降级）→ 补生成一次并刷新缓存
   └─ 未命中 → 规则引擎 + LLM 语义提取
             ├─ 正常 → 生成锐评与锦囊 → 写缓存
             └─ 降级（aiDegraded=true）→ 追加 AI_SERVICE_DOWN 系统信号 → **不写缓存**
```

**“降级结果不写缓存”是刻意设计**：AI 挂掉时锐评为 `null`，如果写进缓存，24 小时内即使模型恢复，同一段 JD 也永远拿不到锐评，用户视角就是“这功能永久坏了”。

### 2.5 实测

```
第一次 POST /api/audits    ms=3290   （真实模型调用）
第二次 POST 相同 JD         ms=11     （日志：JD 解析结果缓存命中）
同一 JD 的 GET /api/audits/{id}       （日志：缓存命中，未触发模型）
```

### 2.6 边界说明

- 进程内缓存，重启即失效；
- 多实例部署时各实例各持一份，不共享（要共享需换 Redis）；
- 只缓存“解析产物”，不缓存 HTTP 响应，因此不影响分页、权限等后续扩展。

---

## 三、Knife4j / OpenAPI 接口文档

### 3.1 依赖组合

```xml
<properties>
    <knife4j.version>4.5.0</knife4j.version>
    <!-- knife4j 4.5.0 自带 springdoc 2.3.x，与 Spring Boot 3.4+ 不兼容，统一升到 2.8.x -->
    <springdoc.version>2.8.9</springdoc.version>
</properties>

<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>${knife4j.version}</version>
    <exclusions>
        <exclusion>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.version}</version>
</dependency>
```

关键点：**排除 knife4j 自带的旧 springdoc**，显式引入 2.8.9。否则 Boot 3.5.7 下版本冲突。

### 3.2 冲突与修复（NoSuchMethodError）

升级后 `/v3/api-docs` 仍然 500，堆栈指向：

```
java.lang.NoSuchMethodError:
org.springdoc.core.properties.SpringDocConfigProperties.getGroupConfigs()Ljava/util/List;
```

原因：knife4j 4.5.0 的 `Knife4jOpenApiCustomizer.addOrderExtension()` 是按 springdoc 2.3 编译的，取 `List`；springdoc 2.8 该方法返回 `Set`。二进制不兼容。

修复：覆盖同名 Bean（原方法带 `@ConditionalOnMissingBean`），只跳过排序扩展——它只负责 UI 里的分组排序，不影响文档内容：

```java
@Bean
public Knife4jOpenApiCustomizer knife4jOpenApiCustomizer(
        Knife4jProperties properties, SpringDocConfigProperties springDocConfigProperties) {
    return new Knife4jOpenApiCustomizer(properties, springDocConfigProperties) {
        @Override
        public void customise(OpenAPI openApi) {
            // 空实现：跳过与 springdoc 2.8 不兼容的排序扩展
        }
    };
}
```

### 3.3 安全收敛：默认关闭

接口文档暴露内部结构，对生产环境是信息泄露面，因此在 `application.properties` 默认关闭：

```properties
springdoc.api-docs.enabled=false
springdoc.api-docs.path=/v3/api-docs
springdoc.swagger-ui.path=/swagger-ui.html
knife4j.enable=false
```

配置类改为条件装配，只在显式开启时才创建 Bean：

```java
@Configuration
@ConditionalOnProperty(name = "knife4j.enable", havingValue = "true")
public class Knife4jConfig {
    // OpenAPI 元信息 + GroupedOpenApi("/api/**")
}
```

本地调试时按需开启：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--springdoc.api-docs.enabled=true --knife4j.enable=true"
```

**实测关闭后的表现**：

```
/v3/api-docs     → 404
/swagger-ui.html → 404
/doc.html        → 200（仅 Knife4j 静态外壳，无任何接口数据）
```

这里要如实表述：`/doc.html` 的静态资源由 starter 直接提供，关闭 `knife4j.enable` 只让它拿不到接口描述，页面本身仍能打开但为空。不要声称“页面也被封了”。

**踩坑记录**：只把 `knife4j.enable` 改成 false、却没给 `Knife4jConfig` 加条件时，Spring 上下文会启动失败——因为自定义 Bean 依赖 `Knife4jProperties`，而自动配置已因开关关闭而不再提供该 Bean。一次性 22 个测试全红，就是这个问题。

---

## 四、H2 一键演示模式

### 4.1 动机

比赛要求“评委拿到代码后 10 分钟内能跑起来”。本地 MySQL 是最大的不确定性（版本、密码、端口、建库），所以提供一条**零数据库依赖**的启动路径。

### 4.2 依赖

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

`runtime` 同时覆盖两个场景：demo profile 运行、`test` profile 的内存库。测试侧无需再加一份 test 作用域依赖。

### 4.3 配置全文

```properties
# ============================================================
# 一键演示模式（demo profile）
# 启动：mvn spring-boot:run -Dspring-boot.run.profiles=demo
# 特点：H2 内存数据库 + MySQL 兼容模式，无需安装任何数据库
# ============================================================

spring.datasource.url=jdbc:h2:mem:jobaudit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# 生产迁移脚本含 ENGINE / CHARSET / COMMENT 等 MySQL 专有子句，H2 无法执行
spring.flyway.enabled=false
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:db/demo/schema-h2.sql

# 演示模式不强制要求真实 Key
langchain4j.open-ai.chat-model.api-key=${LLM_API_KEY:demo-mode-no-key}

# 关闭真实模型外呼：语义提取 / 锐评 / 反问话术全部瞬时降级
jobaudit.ai.enabled=false
```

### 4.4 为什么 demo 模式关掉 Flyway

V1/V2 迁移脚本里有 MySQL 专有语法：

```sql
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

H2 在 `MODE=MySQL` 下仍不接受 `ENGINE/CHARSET/COMMENT`，实测 V2 直接失败：

```
Migration of schema "public" to version "2 - add ai verdict" failed
```

因此 demo 走 `spring.sql.init` 加载手写的 H2 DDL（`db/demo/schema-h2.sql`），结构与 V1+V2 对齐；生产环境仍由 Flyway 管理，两条路径互不影响。

同时放弃了 `flyway-database-h2` 依赖：Maven Central 没有与 Boot 管理版本（11.7.2）对应的 artifact，而 `flyway-core` 本身已能识别 H2，无需额外模块。

### 4.5 演示模式不发外呼

早期 demo 用占位 Key 会真的去打 `api.deepseek.com`，拿到 401 后重试，评委点“开始审计”要等 10~30 秒，观感像卡死。

修正：新增配置开关 `jobaudit.ai.enabled`（默认 `true`，仅 demo 置 false），三处外呼统一短路：

| 方法 | 行为 |
|---|---|
| `SignalExtractionService.extract()` | 直接返回 `pendingReview(null)` |
| `AuditService.generateVerdict()` | 返回 `null` |
| `AuditService.callQuestionsModel()` | 返回空列表 |

默认 profile 与 test profile 行为不变。

### 4.6 启动脚本

`start-demo.bat`：

```bat
@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ==========================================================
echo   job-audit 一键演示模式（H2 内存数据库，无需安装 MySQL）
echo   启动完成请访问：http://localhost:8080/api/health
echo   前端页面：进入 frontend 目录执行 npm install，再 npm run dev
echo ==========================================================
call mvn spring-boot:run -Dspring-boot.run.profiles=demo
pause
```

三个细节：`chcp 65001` 防中文乱码、`cd /d "%~dp0"` 保证双击时工作目录正确、`pause` 防异常退出时窗口闪退。

`start-demo.sh` 对应 `mvn spring-boot:run -Dspring-boot.run.profiles=demo`；`.gitattributes` 已声明 `*.sh text eol=lf`，避免 Windows 检出成 CRLF 后 `bash` 报 `\r` 错误。

**PowerShell 坑**：手敲 `mvn spring-boot:run -Dspring-boot.run.profiles=demo` 时，PowerShell 会在 `-` 处断词，报 `.run.profiles=demo` 无法识别。必须加引号 `"-Dspring-boot.run.profiles=demo"`；用 `.bat` / `cmd` 不受影响，所以交付时优先让评委双击脚本。

### 4.7 实测证据

无 `LLM_API_KEY`、无 MySQL，demo profile 启动：

```
GET  /api/health        → 200 {"status":"ok","project":"job-audit"}
POST /api/audits        → 200，elapsed_ms=371
signals=5（rules 4 + AI_SERVICE_DOWN）
GET  /api/audits/1      → 200，signals 一致
CORS 5173 / 5174        → 均通过
```

371 ms 与修复前 10~30 秒的对比，就是“关闭外呼”开关的价值。

---

## 五、验证汇总

| 项 | 结果 |
|---|---|
| 后端测试 | `Tests run: 22, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS` |
| 测试环境 | H2 内存库（`jdbc:h2:mem:job_audit_test`），无 MySQL、无 Key、无外网 |
| demo 启动 | 双击脚本 → 8 秒内 Tomcat 就绪，Flyway 未参与，H2 建表成功 |
| 缓存效果 | 二次相同 JD 请求 11 ms，日志确认“缓存命中”，无模型调用 |
| 文档开关 | 关闭后 `/v3/api-docs`、`/swagger-ui.html` 均 404 |

---

## 六、经验小结

1. **缓存要缓存“完整产物”**：只缓存 signals 时，锐评仍会重复调用模型；把 signals + aiVerdict + avoidanceTips 打包成一个 `CachedAudit`，一次命中才真正零调用。
2. **缓存必须做副本隔离**：ORM 会回填主键，直接缓存对象引用必然产生脏数据，且这种 bug 在单次请求里看不出来。
3. **降级结果不进缓存**：否则 AI 恢复后仍要等 TTL 过期，用户体验是“永久损坏”。
4. **依赖冲突要看二进制兼容性**：knife4j 与 springdoc 的 `List/Set` 差异属于 `NoSuchMethodError`，编译期完全正常，只有运行到 `/v3/api-docs` 才爆。
5. **开关要连 Bean 一起管**：只改配置不改 `@ConditionalOnProperty`，关闭功能反而会让上下文启动失败。
6. **演示模式要“真的不发请求”**：占位 Key 不等于不调用，必须有显式开关短路外呼，否则等待时间会毁掉第一印象。
