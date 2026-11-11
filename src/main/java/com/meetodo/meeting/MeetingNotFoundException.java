package com.meetodo.meeting;

public class MeetingNotFoundException extends RuntimeException {
    public MeetingNotFoundException() {
        super("Meeting was not found.");
    }
}
