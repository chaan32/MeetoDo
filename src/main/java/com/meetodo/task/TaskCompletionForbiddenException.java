package com.meetodo.task;

public class TaskCompletionForbiddenException extends RuntimeException {
    public TaskCompletionForbiddenException() { super("담당자만 완료할 수 있어요"); }
}
