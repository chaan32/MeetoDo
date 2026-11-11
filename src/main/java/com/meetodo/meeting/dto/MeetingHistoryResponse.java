package com.meetodo.meeting.dto;

import java.time.Instant;
import java.util.List;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingStatus;

public record MeetingHistoryResponse(Long meetingId, Long duplicateOfMeetingId, String rawText, MeetingStatus status,
                                     String summary, Instant createdAt,
                                     List<MeetingTaskResponse> tasks, int unassignedCount,
                                     Long processingTimeMs, int llmCallCount, int toolCallCount,
                                     int validationFailureCount) {
    public static MeetingHistoryResponse from(Meeting meeting, MeetingResponse result) {
        return new MeetingHistoryResponse(meeting.getId(), meeting.getDuplicateOfMeetingId(),
                meeting.getRawText(), meeting.getStatus(),
                meeting.getSummary(), meeting.getCreatedAt(), result.tasks(), result.unassignedCount(),
                meeting.getProcessingTimeMs(), meeting.getLlmCallCount(), meeting.getToolCallCount(),
                meeting.getValidationFailureCount());
    }
}
