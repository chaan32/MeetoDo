package com.meetodo.meeting.dto;

import java.time.Instant;
import com.meetodo.notification.NotificationStatus;
import java.util.List;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingStatus;

public record MeetingHistoryResponse(Long meetingId, Long duplicateOfMeetingId, String rawText, MeetingStatus status,
                                     String summary, Instant createdAt,
                                     List<MeetingTaskResponse> tasks, int unassignedCount,
                                     Long processingTimeMs, int llmCallCount, int toolCallCount,
                                     int validationFailureCount, NotificationStatus notificationStatus, String notificationType,
                                     String notificationErrorCode, Instant notifiedAt, com.meetodo.meeting.MeetingSource source,
                                     String sttModel, Long transcriptionTimeMs) {
    public static MeetingHistoryResponse from(Meeting meeting, MeetingResponse result) {
        return new MeetingHistoryResponse(meeting.getId(), meeting.getDuplicateOfMeetingId(),
                meeting.getRawText(), meeting.getStatus(),
                meeting.getSummary(), meeting.getCreatedAt(), result.tasks(), result.unassignedCount(),
                meeting.getProcessingTimeMs(), meeting.getLlmCallCount(), meeting.getToolCallCount(),
                meeting.getValidationFailureCount(), result.notificationStatus(), result.notificationType(),
                result.notificationErrorCode(), result.notifiedAt(), meeting.getSource(), meeting.getSttModel(), meeting.getTranscriptionTimeMs());
    }
}
