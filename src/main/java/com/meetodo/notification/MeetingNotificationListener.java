package com.meetodo.notification;

import com.meetodo.meeting.MeetingResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MeetingNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(MeetingNotificationListener.class);
    private final ObjectProvider<Notifier> notifiers;
    private final MeetingResults results;
    private final NotificationStateService states;
    private final NotificationMessageFactory messages = new NotificationMessageFactory();

    public MeetingNotificationListener(ObjectProvider<Notifier> notifiers, MeetingResults results, NotificationStateService states) {
        this.notifiers = notifiers; this.results = results; this.states = states;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(MeetingFinishedEvent event) {
        try {
            if (!states.pending(event.meetingId())) return;
            Notifier notifier = notifiers.getIfAvailable();
            if (notifier == null) throw new NotificationDeliveryException("CONFIGURATION_ERROR");
            notifier.send(messages.from(results.get(event.meetingId())));
            states.sent(event.meetingId());
        } catch (Exception failure) {
            String code = failure instanceof NotificationDeliveryException delivery
                    ? NotificationStateService.safeCode(delivery.getCode()) : "INTERNAL_ERROR";
            // Provider exceptions can contain credentials; record only our bounded code, never the throwable.
            log.warn("Meeting notification failed: meetingId={}, code={}", event.meetingId(), code);
            try { states.failed(event.meetingId(), code); }
            catch (Exception persistenceFailure) { log.warn("Notification status update failed: meetingId={}", event.meetingId()); }
        }
    }
}
