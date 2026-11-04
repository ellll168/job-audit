package com.example.jobaudit;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * LLM 连通性冒烟测试：验证 ChatModel 自动装配 + API key + base-url 可用。
 */
@SpringBootTest
class ChatModelSmokeTest {

    @Autowired
    ChatModel chatModel;

    @Test
    void smokeTest() {
        String reply = chatModel.chat("你好，请只回复两个字：成功");
        System.out.println("模型回复：" + reply);
        assert reply != null && !reply.isEmpty();
    }
}
