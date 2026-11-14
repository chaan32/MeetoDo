package com.meetodo.slack;

import com.slack.api.methods.MethodsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackQuestionConfigurationTests {
    @SuppressWarnings("unchecked")
    @Test void questionHandlerWorksWithoutMeetingNotifier() {
        // 알림 설정 때문에 회의 입력 빈이 없어도 읽기 전용 질문은 등록한다.
        var questions=mock(SlackQuestionInputService.class);
        var provider=mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(questions);
        var app=new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN","test-token"),
                mock(SlackBotService.class),mock(ObjectProvider.class),mock(SlackTaskEditService.class),mock(SlackHomeService.class),
                mock(SlackMemberManagementService.class),mock(ObjectProvider.class),mock(ObjectProvider.class),provider);
        verify(questions).registerOnly(app);
    }
    @SuppressWarnings("unchecked")
    @Test void meetingHandlerReceivesQuestionsWithoutDuplicateCommandRegistration() {
        // 기존 명령이 있으면 질문 전용 명령을 중복 등록하지 않는다.
        var questions=mock(SlackQuestionInputService.class); var meetings=mock(SlackMeetingInputService.class);
        var provider=mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(questions);
        var meetingProvider=mock(ObjectProvider.class); when(meetingProvider.getIfAvailable()).thenReturn(meetings);
        var app=new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN","test-token"),
                mock(SlackBotService.class),meetingProvider,mock(SlackTaskEditService.class),mock(SlackHomeService.class),
                mock(SlackMemberManagementService.class),mock(ObjectProvider.class),mock(ObjectProvider.class),provider);
        verify(meetings).setQuestions(questions); verify(meetings).register(app); verifyNoInteractions(questions);
    }
    @Test void questionExecutorHasSeparateBoundedQueue() {
        // 질문 큐는 회의와 분리되고 포화 시 호출자 스레드에서 실행하지 않는다.
        var executor=new SlackBotConfiguration().slackQuestionExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(1); assertThat(executor.getMaxPoolSize()).isEqualTo(1);
        assertThat(executor.getQueueCapacity()).isEqualTo(10);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("slack-question-");
    }
    @Test void questionBeansRequireRagFlagAndExternalBotConfiguration() throws Exception {
        // RAG 비활성 구성은 질문 서비스나 별도 실행기를 요구하지 않는다.
        for(var method:SlackBotConfiguration.class.getDeclaredMethods()) {
            if(java.util.List.of("slackQuestionExecutor","slackQuestionInputService").contains(method.getName()))
                assertThat(method.getAnnotation(org.springframework.context.annotation.Conditional.class).value())
                        .containsExactly(com.meetodo.rag.config.RagEnabled.class);
        }
        assertThat(SlackBotConfiguration.class.getAnnotation(org.springframework.context.annotation.Profile.class).value())
                .containsExactly("(local | docker) & !test");
    }
}
