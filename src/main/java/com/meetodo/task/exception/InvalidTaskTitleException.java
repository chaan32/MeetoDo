package com.meetodo.task.exception;

public class InvalidTaskTitleException extends RuntimeException {
    private final String code;

    public InvalidTaskTitleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
