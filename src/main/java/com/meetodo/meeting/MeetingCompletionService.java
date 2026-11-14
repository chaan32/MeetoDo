package com.meetodo.meeting;

import com.meetodo.notification.MeetingFinishedEvent;
import com.meetodo.notification.Notifier;
import com.meetodo.notification.NotificationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingCompletionService {
    private final MeetingRepository meetings;
    private final ObjectProvider<Notifier> notifiers;
    private final ApplicationEventPublisher events;

    public MeetingCompletionService(MeetingRepository meetings, ObjectProvider<Notifier> notifiers, ApplicationEventPublisher events) {
        this.meetings = meetings; this.notifiers = notifiers; this.events = events;
    }

    @Transactional
    public void finish(Meeting meeting) {
        Notifier notifier = notifiers.getIfAvailable();
        meeting.prepareNotification(notifier == null ? null : notifier.type());
        meetings.saveAndFlush(meeting);
        if (meeting.getStatus() == MeetingStatus.DONE)
            events.publishEvent(new com.meetodo.rag.index.MeetingProcessedEvent(meeting.getId()));
        if (meeting.getNotificationStatus() == NotificationStatus.PENDING)
            events.publishEvent(new MeetingFinishedEvent(meeting.getId()));
    }
}
