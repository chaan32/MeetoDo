package com.meetodo.slack;

import com.meetodo.task.TaskReassignedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class SlackTaskReassignmentListenerTests {
    @SuppressWarnings("unchecked")
    final ObjectProvider<SlackBotService> bots = mock(ObjectProvider.class);
    final TaskReassignedEvent event = new TaskReassignedEvent(7L, 1L, "UOLD", 2L, "UADMIN");

    @Test void committedReassignmentDispatchesFullOldAndNewOwnerEvent() {
        // 인계 대상과 변경자를 잃지 않도록 이벤트 전체를 봇에 전달한다.
        var bot = mock(SlackBotService.class); when(bots.getIfAvailable()).thenReturn(bot);
        new SlackTaskReassignmentListener(bots).afterCommit(event);
        verify(bot).reassigned(event);
    }

    @Test void disabledBotNeedsNoSlackClient() {
        // 봇 비활성 환경은 재배정에도 Slack 클라이언트가 필요 없다.
        assertThatCode(() -> new SlackTaskReassignmentListener(bots).afterCommit(event)).doesNotThrowAnyException();
    }

    @Test void notificationFailureCannotUndoCommittedReassignment() {
        // 커밋 후 알림 예외는 재배정 호출자에게 전파하지 않는다.
        var bot = mock(SlackBotService.class); when(bots.getIfAvailable()).thenReturn(bot);
        doThrow(new IllegalStateException("provider secret")).when(bot).reassigned(event);
        assertThatCode(() -> new SlackTaskReassignmentListener(bots).afterCommit(event)).doesNotThrowAnyException();
    }
}
