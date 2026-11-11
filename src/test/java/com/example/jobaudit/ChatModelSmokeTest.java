package com.example.jobaudit;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * LLM 连通性冒烟测试（真实网络调用，默认跳过）。
 * 手动运行（需本机设置 LLM_API_KEY）：
 * mvn test -Dtest=ChatModelSmokeTest -Dllm.live=true
 * <p>
 * api-key 用占位符兜底，保证没有环境变量时上下文也能正常启动，不会因为未解析的
 * ${LLM_API_KEY} 导致整个 mvn test 失败。
 */
@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=${LLM_API_KEY:test-key}")
@ActiveProfiles("test")
class ChatModelSmokeTest {

    @Autowired
    ChatModel chatModel;

    @Test
    void smokeTest() {
        Assumptions.assumeTrue(Boolean.getBoolean("llm.live"),
                "真实模型调用测试，默认跳过。运行方式：mvn test -Dtest=ChatModelSmokeTest -Dllm.live=true");

        String reply = chatModel.chat("你好，请只回复两个字：成功");
        System.out.println("模型回复：" + reply);
        assert reply != null && !reply.isEmpty();
    }
}
