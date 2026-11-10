package com.meetodo;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatClientMockTests {

    @Test
    void usesMockModelInsteadOfCallingOpenAi() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("연결 테스트 응답")))));

        String response = ChatClient.create(model).prompt()
                .user("테스트 요청")
                .call()
                .content();

        assertThat(response).isEqualTo("연결 테스트 응답");
        verify(model).call(any(Prompt.class));
    }
}
