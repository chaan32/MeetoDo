package com.meetodo.notification;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class NotificationStateService {
    private final MeetingRepository meetings;
    private final Clock clock;
    public NotificationStateService(MeetingRepository meetings, Clock clock) { this.meetings = meetings; this.clock = clock; }

    public boolean pending(Long id) {
        return meetings.findById(id).map(m -> m.getNotificationStatus() == NotificationStatus.PENDING).orElse(false);
    }

    public void sent(Long id) {
        Meeting meeting = meetings.findById(id).orElseThrow();
        if (meeting.getNotificationStatus() == NotificationStatus.PENDING) meeting.notificationSent(Instant.now(clock));
    }

    public void failed(Long id, String code) {
        Meeting meeting = meetings.findById(id).orElseThrow();
        if (meeting.getNotificationStatus() == NotificationStatus.PENDING) meeting.notificationFailed(safeCode(code));
    }

    public static String safeCode(String code) {
        return code != null && Set.of("HTTP_ERROR", "TIMEOUT_DELIVERY_UNKNOWN", "TRANSPORT_ERROR", "CONFIGURATION_ERROR").contains(code)
                ? code : "INTERNAL_ERROR";
    }
}
