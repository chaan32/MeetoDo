package com.meetodo.meeting.dto;

public record MeetingProcessingError(String code, String message, Long meetingId) {
}
