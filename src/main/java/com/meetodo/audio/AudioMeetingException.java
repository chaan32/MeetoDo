package com.meetodo.audio;

import org.springframework.http.HttpStatus;

public class AudioMeetingException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AudioMeetingException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
