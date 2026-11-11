package com.meetodo.meeting;

public class MeetingAgentDisabledException extends RuntimeException {
    public MeetingAgentDisabledException() {
        super("Meeting processing is disabled in this profile.");
    }
}
