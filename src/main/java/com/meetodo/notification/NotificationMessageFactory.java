package com.meetodo.notification;

import com.meetodo.meeting.MeetingStatus;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import java.util.List;

public class NotificationMessageFactory {
    public NotificationMessage from(MeetingResponse response) {
        String footer = "회의 " + response.meetingId() + " · 처리 시간 "
                + (response.processingTimeMs() == null ? "측정 없음" : response.processingTimeMs() + "ms");
        if (response.status() == MeetingStatus.FAILED) {
            return new NotificationMessage(response.meetingId(), "⚠️ 회의 처리 실패",
                    List.of("회의 처리 중 오류가 발생했습니다.", "GET /api/meetings/" + response.meetingId() + "에서 상태를 확인하세요."), List.of(), footer);
        }
        int total = response.tasks().size();
        long unassigned = response.tasks().stream().filter(task -> task.assigneeName() == null).count();
        if (response.duplicateOfMeetingId() != null) {
            return new NotificationMessage(response.meetingId(), "📝 같은 할 일 묶음 확인 (할 일 " + total + "건)",
                    List.of("원본 회의 " + response.duplicateOfMeetingId() + "과 같은 할 일 묶음입니다.",
                            "새로 등록 0건 · 기존 할 일 " + total + "건 · 담당자 미정 " + unassigned + "건"), List.of(), footer);
        }
        long existing = response.tasks().stream().filter(task -> "EXISTING".equals(task.resultType())).count();
        return new NotificationMessage(response.meetingId(), "📝 회의 정리 완료 (할 일 " + total + "건)",
                List.of("새로 등록 " + (total - existing) + "건 · 기존 할 일 " + existing + "건", "담당자 미정 " + unassigned + "건"),
                response.tasks().stream().map(this::taskLine).toList(), footer);
    }

    private String taskLine(MeetingTaskResponse task) {
        String assignee = task.assigneeName() == null ? "담당자 미정" : task.assigneeName()
                + (task.assigneeTeam() == null || task.assigneeTeam().isBlank() ? "" : "(" + task.assigneeTeam() + ")");
        String line = ("EXISTING".equals(task.resultType()) ? "♻️ " : "🆕 ") + assignee + " · " + task.title()
                + " · " + (task.dueDate() == null ? "마감일 없음" : task.dueDate());
        if (task.mentionCount() >= 2) line += " · " + task.mentionCount() + "번째 언급";
        if (task.warning() != null && !task.warning().isBlank()) line += " · ⚠️ " + task.warning();
        return line.replaceAll("[\\r\\n]+", " ");
    }
}
