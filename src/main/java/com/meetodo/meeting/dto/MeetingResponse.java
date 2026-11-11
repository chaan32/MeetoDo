package com.meetodo.meeting.dto;

import java.util.List;
import java.time.Instant;
import com.meetodo.notification.NotificationStatus;
import java.util.Map;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingStatus;
import com.meetodo.task.Task;

public record MeetingResponse(Long meetingId, Long duplicateOfMeetingId, MeetingStatus status, String summary,
                              List<MeetingTaskResponse> tasks, int unassignedCount,
                              Long processingTimeMs, int llmCallCount, int toolCallCount,
                              int validationFailureCount, NotificationStatus notificationStatus, String notificationType,
                              String notificationErrorCode, Instant notifiedAt) {
    public static MeetingResponse from(Meeting meeting, List<Task> tasks, Map<Long, String> resultTypes,
                                       Map<Long, Long> mentionCounts) {
        return from(meeting, tasks, resultTypes, mentionCounts, Map.of(), Map.of());
    }

    public static MeetingResponse from(Meeting meeting, List<Task> tasks, Map<Long, String> resultTypes,
                                       Map<Long, Long> mentionCounts, Map<Long, String> warnings, Map<Long, String> reasons) {
        List<MeetingTaskResponse> items = tasks.stream()
                .map(task -> MeetingTaskResponse.from(task, resultTypes.getOrDefault(task.getId(), "CREATED"),
                        mentionCounts.getOrDefault(task.getId(), 0L), warnings.get(task.getId()), reasons.get(task.getId())))
                .toList();
        int unassigned = (int) tasks.stream().filter(task -> task.getAssignee() == null).count();
        return new MeetingResponse(meeting.getId(), meeting.getDuplicateOfMeetingId(), meeting.getStatus(),
                meeting.getSummary(), items,
                unassigned, meeting.getProcessingTimeMs(), meeting.getLlmCallCount(),
                meeting.getToolCallCount(), meeting.getValidationFailureCount(), meeting.getNotificationStatus(),
                meeting.getNotificationType(), meeting.getNotificationErrorCode(), meeting.getNotifiedAt());
    }
}
