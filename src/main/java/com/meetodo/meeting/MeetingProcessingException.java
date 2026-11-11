package com.meetodo.meeting;

public class MeetingProcessingException extends RuntimeException {
    private final Long meetingId;

    public MeetingProcessingException(Long meetingId) {
        super("Meeting processing failed.");
        this.meetingId = meetingId;
    }

    public Long getMeetingId() { return meetingId; }
}
