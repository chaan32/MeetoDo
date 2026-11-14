package com.meetodo.slack;

import com.meetodo.member.registration.MemberRegistrationChangedEvent;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlackRegistrationChangedListener {
    private final ObjectProvider<SlackRegistrationNotifications> notifications;
    public SlackRegistrationChangedListener(ObjectProvider<SlackRegistrationNotifications> notifications) {
        this.notifications = notifications;
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(MemberRegistrationChangedEvent event) {
        try {
            var service = notifications.getIfAvailable();
            if (service != null) service.changed(event.requestId());
        } catch (Exception ignored) {
            LoggerFactory.getLogger(getClass()).warn("Slack registration notification failed: INTERNAL_ERROR");
        }
    }
}
