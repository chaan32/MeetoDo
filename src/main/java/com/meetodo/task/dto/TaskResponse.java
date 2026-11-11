package com.meetodo.task.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.meetodo.task.Task;
import com.meetodo.task.TaskStatus;

public record TaskResponse(Long id, String title, MemberResponse assignee,
                           LocalDate dueDate, TaskStatus status, Long meetingId, Instant createdAt,
                           long mentionCount) {
    public static TaskResponse from(Task task) {
        return from(task, 0);
    }

    public static TaskResponse from(Task task, long mentionCount) {
        MemberResponse member = task.getAssignee() == null ? null : new MemberResponse(
                task.getAssignee().getId(), task.getAssignee().getName(), task.getAssignee().getTeam(),
                task.getAssignee().getSlackUserId());
        return new TaskResponse(task.getId(), task.getTitle(), member, task.getDueDate(),
                task.getStatus(), task.getMeetingId(), task.getCreatedAt(), mentionCount);
    }
}
