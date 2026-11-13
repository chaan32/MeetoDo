package com.meetodo.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meetodo.task.TaskStatus;
import java.time.LocalDate;

public class UpdateTaskRequest {
    private String title;
    private LocalDate dueDate;
    private TaskStatus status;
    private boolean titleProvided;
    private boolean dueDateProvided;
    private boolean statusProvided;
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; this.titleProvided = true; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) {
        if (dueDate != null && !dueDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("마감일은 yyyy-MM-dd 형식이어야 합니다.");
        this.dueDate = dueDate == null ? null : LocalDate.parse(dueDate);
        this.dueDateProvided = true;
    }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; this.statusProvided = true; }
    @JsonIgnore public boolean isTitleProvided() { return titleProvided; }
    @JsonIgnore public boolean isDueDateProvided() { return dueDateProvided; }
    @JsonIgnore public boolean isStatusProvided() { return statusProvided; }
}
