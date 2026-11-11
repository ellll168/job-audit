package com.example.jobaudit.service;

import com.example.jobaudit.domain.ExtractedSignal;
import com.example.jobaudit.mapper.JobAuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 故障注入测试：验证批量写入信号失败时，主记录 job_audit 的插入被事务回滚。
 */
// 占位 Key：测试不依赖真实 LLM_API_KEY，无环境变量时也能启动上下文
@SpringBootTest(properties = {
        "LLM_API_KEY=test-key",
        "langchain4j.open-ai.chat-model.api-key=test-key"})
@ActiveProfiles("test")
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
