package com.meetodo.meeting.dto;

import java.time.LocalDate;

import com.meetodo.task.Task;

public record MeetingTaskResponse(Long taskId, String title, String assigneeName, LocalDate dueDate,
                                  String assigneeTeam, String resultType, long mentionCount, String warning, String unassignedReason) {
    public static MeetingTaskResponse from(Task task, String resultType, long mentionCount, String warning, String unassignedReason) {
        return new MeetingTaskResponse(task.getId(), task.getTitle(),
                task.getAssignee() == null ? null : task.getAssignee().getName(), task.getDueDate(),
                task.getAssignee() == null ? null : task.getAssignee().getTeam(), resultType, mentionCount, warning, unassignedReason);
    }
}
