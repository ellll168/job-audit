package com.example.jobaudit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "LLM_API_KEY=test-key",
        "langchain4j.open-ai.chat-model.api-key=test-key"})
@ActiveProfiles("test")
class JobAuditApplicationTests {

    @Test
    void contextLoads() {
    }

}
