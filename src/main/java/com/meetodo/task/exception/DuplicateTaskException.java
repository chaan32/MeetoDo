package com.meetodo.task.exception;

public class DuplicateTaskException extends RuntimeException {
    private final Long existingTaskId;
    public DuplicateTaskException(Long existingTaskId) {
        super("같은 담당자의 미완료 할 일이 이미 존재합니다.");
        this.existingTaskId = existingTaskId;
    }
    public Long getExistingTaskId() { return existingTaskId; }
}
