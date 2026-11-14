package com.meetodo;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Simulates dev profiles with a mock replacing the provider bean. No real LLM calls.
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-a-secret",
        "OPENAI_API_KEY=test-key-not-a-secret",
        "NOTIFIER_TYPE=none",
        "spring.datasource.url=jdbc:h2:mem:llm-ping-test"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "openai"})
@ExtendWith(OutputCaptureExtension.class)
class LlmPingControllerTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    RetryTemplate retryTemplate;

    @MockitoBean
    OpenAiChatModel model;

    // 검증: returnsTextAndMeasuresCallTimeWithAFixedPrompt 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsTextAndMeasuresCallTimeWithAFixedPrompt(CapturedOutput output) throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(25);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
        });

        mvc.perform(get("/api/dev/llm-ping").param("question", "user-input-must-not-be-forwarded"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.text").value("OK"))
                .andExpect(jsonPath("$.elapsedMs").value(greaterThanOrEqualTo(20)));

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).isEqualTo("Reply with OK only.");
        assertThat(output).doesNotContain("Reply with OK only.", "test-key-not-a-secret");
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/dev/llm-ping'].get").exists());
    }

    // 검증: hidesProviderExceptionFromResponseAndLogs 시나리오의 기대 동작을 확인한다.
    @Test
    void hidesProviderExceptionFromResponseAndLogs(CapturedOutput output) throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> retryTemplate.execute(context -> {
            throw new NonTransientAiException("sensitive-provider-body-and-key");
        }));

        mvc.perform(get("/api/dev/llm-ping"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"error\":\"OpenAI connection check failed.\"}"));

        assertThat(output).doesNotContain("sensitive-provider-body-and-key");
    }

    // 검증: doesNotReportEmptyModelOutputAsSuccess 시나리오의 기대 동작을 확인한다.
    @Test
    void doesNotReportEmptyModelOutputAsSuccess() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));

        mvc.perform(get("/api/dev/llm-ping"))
                .andExpect(status().isBadGateway());
    }
}
