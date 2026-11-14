package com.meetodo.slack;

import com.meetodo.member.registration.MemberRegistrationChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class SlackRegistrationChangedListenerTests {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SlackRegistrationNotifications> notifications = mock(ObjectProvider.class);

    @Test void committedRegistrationDispatchesToAvailableBot() {
        // 활성 봇에 커밋된 요청 ID를 전달한다.
        var service = mock(SlackRegistrationNotifications.class); when(notifications.getIfAvailable()).thenReturn(service);
        new SlackRegistrationChangedListener(notifications).afterCommit(new MemberRegistrationChangedEvent(7L));
        verify(service).changed(7L);
    }
    @Test void disabledBotNeedsNoSlackClient() {
        // 봇 비활성 환경은 알림 서비스나 외부 연결 없이 처리된다.
        assertThatCode(() -> new SlackRegistrationChangedListener(notifications).afterCommit(new MemberRegistrationChangedEvent(7L)))
                .doesNotThrowAnyException();
    }
    @Test void notificationFailureNeverChangesCommittedResult() {
        // 커밋 뒤 알림 실패를 요청 처리 호출자에게 전파하지 않는다.
        var service = mock(SlackRegistrationNotifications.class); when(notifications.getIfAvailable()).thenReturn(service);
        doThrow(new IllegalStateException("provider private text")).when(service).changed(7L);
        assertThatCode(() -> new SlackRegistrationChangedListener(notifications).afterCommit(new MemberRegistrationChangedEvent(7L)))
                .doesNotThrowAnyException();
    }
}
