package com.meetodo.meeting.dto;

import java.time.LocalDate;
import com.meetodo.task.Task;
import com.meetodo.task.UnresolvedAssignee;

public record MeetingTaskResponse(Long taskId, String title, String assigneeName, LocalDate dueDate,
                                  String assigneeTeam, String resultType, long mentionCount, String warning,
                                  String unassignedReason, UnresolvedAssignee unresolvedAssignee) {
    public MeetingTaskResponse(Long taskId, String title, String assigneeName, LocalDate dueDate, String assigneeTeam,
                               String resultType, long mentionCount, String warning, String unassignedReason) {
        this(taskId, title, assigneeName, dueDate, assigneeTeam, resultType, mentionCount, warning, unassignedReason, null);
    }

    public static MeetingTaskResponse from(Task task, String resultType, long mentionCount, String warning, String reason) {
        return from(task, resultType, mentionCount, warning, reason, task.getUnresolvedAssignee());
    }

    public static MeetingTaskResponse from(Task task, String resultType, long mentionCount, String warning,
                                           String reason, UnresolvedAssignee detail) {
        if (task.getAssignee() != null) { detail = null; warning = null; reason = null; }
        else if (detail != null) { warning = detail.warning(); reason = detail.reason(); }
        return new MeetingTaskResponse(task.getId(), task.getTitle(),
                task.getAssignee() == null ? null : task.getAssignee().getName(), task.getDueDate(),
                task.getAssignee() == null ? null : task.getAssignee().getTeam(), resultType, mentionCount, warning, reason, detail);
    }
}
