package com.meetodo.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import com.meetodo.task.Task;
import com.meetodo.task.TaskStatus;
import com.meetodo.task.UnresolvedAssignee;

public record TaskResponse(Long id, String title, MemberResponse assignee,
                           LocalDate dueDate, TaskStatus status, Long meetingId, Instant createdAt,
                           long mentionCount, UnresolvedAssignee unresolvedAssignee, String warning, String unassignedReason,
                           String completedBySlackUserId, Instant completedAt,
                           String assignedBySlackUserId, Instant assignedAt, boolean deleted, String dueDateWarning) {
    public TaskResponse(Long id, String title, MemberResponse assignee, LocalDate dueDate, TaskStatus status,
                        Long meetingId, Instant createdAt, long mentionCount, UnresolvedAssignee unresolvedAssignee,
                        String warning, String unassignedReason, String completedBySlackUserId, Instant completedAt,
                        String assignedBySlackUserId, Instant assignedAt, boolean deleted) {
        this(id,title,assignee,dueDate,status,meetingId,createdAt,mentionCount,unresolvedAssignee,warning,unassignedReason,
                completedBySlackUserId,completedAt,assignedBySlackUserId,assignedAt,deleted,null);
    }
    public TaskResponse(Long id, String title, MemberResponse assignee, LocalDate dueDate, TaskStatus status,
                        Long meetingId, Instant createdAt, long mentionCount, UnresolvedAssignee unresolvedAssignee,
                        String warning, String unassignedReason, String completedBySlackUserId, Instant completedAt,
                        String assignedBySlackUserId, Instant assignedAt) {
        this(id, title, assignee, dueDate, status, meetingId, createdAt, mentionCount, unresolvedAssignee,
                warning, unassignedReason, completedBySlackUserId, completedAt, assignedBySlackUserId, assignedAt, false);
    }
    public TaskResponse(Long id, String title, MemberResponse assignee, LocalDate dueDate, TaskStatus status,
                        Long meetingId, Instant createdAt, long mentionCount, UnresolvedAssignee unresolvedAssignee,
                        String warning, String unassignedReason, String completedBySlackUserId, Instant completedAt) {
        this(id, title, assignee, dueDate, status, meetingId, createdAt, mentionCount, unresolvedAssignee,
                warning, unassignedReason, completedBySlackUserId, completedAt, null, null);
    }
    public TaskResponse(Long id, String title, MemberResponse assignee, LocalDate dueDate, TaskStatus status,
                        Long meetingId, Instant createdAt, long mentionCount, UnresolvedAssignee unresolvedAssignee,
                        String warning, String unassignedReason) {
        this(id, title, assignee, dueDate, status, meetingId, createdAt, mentionCount, unresolvedAssignee, warning, unassignedReason, null, null);
    }
    public TaskResponse(Long id, String title, MemberResponse assignee, LocalDate dueDate, TaskStatus status,
                        Long meetingId, Instant createdAt, long mentionCount) {
        this(id, title, assignee, dueDate, status, meetingId, createdAt, mentionCount, null, null, null);
    }
    public static TaskResponse from(Task task) { return from(task, 0); }

    public static TaskResponse from(Task task, long mentionCount) {
        MemberResponse member = task.getAssignee() == null ? null : new MemberResponse(
                task.getAssignee().getId(), task.getAssignee().getName(), task.getAssignee().getTeam(),
                task.getAssignee().getSlackUserId());
        UnresolvedAssignee detail = member == null ? task.getUnresolvedAssignee() : null;
        return new TaskResponse(task.getId(), task.getTitle(), member, task.getDueDate(),
                task.getStatus(), task.getMeetingId(), task.getCreatedAt(), mentionCount, detail,
                task.getWarning().isBlank() ? null : task.getWarning(), detail == null ? null : detail.reason(),
                task.getCompletedBySlackUserId(), task.getCompletedAt(), task.getAssignedBySlackUserId(), task.getAssignedAt(), task.isDeleted(), task.getDueDateWarning());
    }
}
