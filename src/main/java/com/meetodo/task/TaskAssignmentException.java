package com.meetodo.task;

public class TaskAssignmentException extends IllegalArgumentException {
    private final String code;
    public TaskAssignmentException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
