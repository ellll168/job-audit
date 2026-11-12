package com.example.jobaudit.service;

import com.example.jobaudit.domain.AuditSignalRecord;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * JD 信号内存缓存：完全相同的 JD 在 24 小时内只做一次规则扫描 + 大模型语义提取。
 * <p>
 * Key 为 JD 文本（空白归一化后）的 SHA-256 摘要，Value 为合并去重后的信号列表 + 毒舌锐评。
 * 存取一律使用副本：持久化层会给写入的记录回填 auditId / createdAt，
 * 若直接缓存同一批对象，缓存里的数据会被上一次落库的 auditId 污染。
 * <p>
 * 进程内缓存，重启即失效；多实例部署时各实例各自持有，不共享。
 */
@Component
public class JdSignalCache {

    private static final Logger log = LoggerFactory.getLogger(JdSignalCache.class);

    /** 与产品口径一致：24 小时 */
    private static final Duration TTL = Duration.ofHours(24);

    /** 上限防止内存无限增长，超出按 LRU 淘汰 */
    private static final long MAX_SIZE = 2_000;

    private final Cache<String, CachedAudit> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(MAX_SIZE)
            .recordStats()
            .build();

    /** 一次 JD 解析的完整产物：信号列表 + 毒舌锐评 + 避坑建议，一起缓存才能彻底免掉模型调用 */
    public record CachedAudit(List<AuditSignalRecord> signals, String aiVerdict, List<String> avoidanceTips) {
    }

    /** 读缓存；未命中返回 empty */
    public Optional<CachedAudit> get(String jdText) {
        String key = keyOf(jdText);
        CachedAudit cached = cache.getIfPresent(key);
        if (cached == null) {
            log.info("JD 解析结果缓存未命中，key={}", key);
            return Optional.empty();
        }
        log.info("JD 解析结果缓存命中，key={}，信号数={}，锐评={}",
                key, cached.signals().size(), cached.aiVerdict());
        return Optional.of(new CachedAudit(copyOf(cached.signals()), cached.aiVerdict(), cached.avoidanceTips()));
    }

    /** 写缓存，写入的是副本，与调用方后续对记录的修改隔离 */
    public void put(String jdText, List<AuditSignalRecord> signals, String aiVerdict, List<String> avoidanceTips) {
        cache.put(keyOf(jdText), new CachedAudit(copyOf(signals), aiVerdict, List.copyOf(avoidanceTips)));
    }

    /**
     * JD 文本 -> 缓存 Key：先去首尾空白、把连续空白折叠为单个空格，
     * 让仅换行/缩进不同的同一段 JD 也能命中；再取 SHA-256 十六进制摘要。
     */
    private String keyOf(String jdText) {
        String normalized = jdText.strip().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private List<AuditSignalRecord> copyOf(List<AuditSignalRecord> signals) {
        return signals.stream().map(this::copy).toList();
    }

    /** 只复制业务字段，id / auditId / createdAt 由持久化层重新回填 */
    private AuditSignalRecord copy(AuditSignalRecord source) {
        AuditSignalRecord record = new AuditSignalRecord();
        record.setSignalType(source.getSignalType());
        record.setSeverity(source.getSeverity());
        record.setReason(source.getReason());
        record.setConfidence(source.getConfidence());
        record.setSource(source.getSource());
        return record;
    }
}
