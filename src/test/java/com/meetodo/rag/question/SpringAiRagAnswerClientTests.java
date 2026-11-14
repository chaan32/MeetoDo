package com.meetodo.rag.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.index.RagSourceSnapshot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpringAiRagAnswerClientTests {
    @Test void singleChatCallHasNoToolCallbacksAndUsesCanonicalContext() {
        // 질문 전용 프롬프트에는 실행 가능한 도구가 없고 서버 출처만 들어간다.
        ChatModel model=mock(ChatModel.class);
        var client=new SpringAiRagAnswerClient(model,new ObjectMapper());
        var source=new RagSourceSnapshot("doc","MEETING",1L,null,0,"회의 원문",Instant.EPOCH,"","ns");
        client.answer("질문",List.of(source));
        var prompt=ArgumentCaptor.forClass(Prompt.class);
        verify(model,times(1)).call(prompt.capture());
        var options=(ToolCallingChatOptions)prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getToolNames()).isEmpty();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(prompt.getValue().getContents()).contains("회의 원문","doc");
    }
}
