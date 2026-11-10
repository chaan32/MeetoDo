package com.meetodo;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// Checks bean wiring only: this test never sends a request to OpenAI.
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-a-secret",
        "spring.ai.openai.chat.options.model=gpt-4o-mini"
})
@ActiveProfiles({"test", "openai"})
class OpenAiConfigurationTests {

    @Autowired
    ChatClient.Builder builder;

    @Autowired
    OpenAiChatModel model;

    @Test
    void configuresChatClientWithoutCallingOpenAi() {
        assertThat(model).isNotNull();
        assertThat(builder.build()).isNotNull();
    }
}
