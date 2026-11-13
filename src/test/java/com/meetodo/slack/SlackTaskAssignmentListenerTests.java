package com.meetodo.slack;

import com.meetodo.task.TaskAssignedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class SlackTaskAssignmentListenerTests {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SlackBotService> bots = mock(ObjectProvider.class);

    @Test void committedAssignmentDispatchesToAvailableBot() {
        // 활성 봇이 있으면 커밋된 배정의 업무 ID와 지정자를 그대로 전달한다.
        var bot = mock(SlackBotService.class); when(bots.getIfAvailable()).thenReturn(bot);
        new SlackTaskAssignmentListener(bots).afterCommit(new TaskAssignedEvent(7L, "UACTOR"));
        verify(bot).assigned(7L, "UACTOR");
    }
    @Test void disabledBotNeedsNoSlackClient() {
        // 봇 비활성 환경의 배정은 Slack 빈을 요구하지 않는다.
        assertThatCode(() -> new SlackTaskAssignmentListener(bots).afterCommit(new TaskAssignedEvent(7L, "UACTOR")))
                .doesNotThrowAnyException();
    }
    @Test void failedSlackNotificationDoesNotFailAssignment() {
        // 커밋 뒤 Slack 오류를 호출자에게 던져 API 배정 성공을 실패로 바꾸지 않는다.
        var bot = mock(SlackBotService.class); when(bots.getIfAvailable()).thenReturn(bot);
        doThrow(new IllegalStateException("provider secret")).when(bot).assigned(7L, "UACTOR");
        assertThatCode(() -> new SlackTaskAssignmentListener(bots).afterCommit(new TaskAssignedEvent(7L, "UACTOR")))
                .doesNotThrowAnyException();
    }
}
