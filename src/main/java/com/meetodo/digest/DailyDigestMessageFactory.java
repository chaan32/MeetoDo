package com.meetodo.digest;

import com.meetodo.notification.NotificationMessage;
import com.meetodo.task.Task;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DailyDigestMessageFactory {
    public NotificationMessage create(DailyDigest digest, List<Task> tasks) {
        LocalDate today = digest.getDigestDate();
        List<String> lines = tasks.stream().sorted(Comparator
                .comparing((Task task) -> task.getAssignee() == null ? null : task.getAssignee().getId(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getDueDate).thenComparing(Task::getId))
                .map(task -> line(task, today)).toList();
        return new NotificationMessage(null, "📋 마감 할 일 알림 (" + today + ")",
                List.of("⏰ 마감 지남 " + digest.getOverdueCount() + "건", "📌 오늘 마감 " + digest.getTodayCount() + "건",
                        "📅 3일 이내 마감 " + digest.getUpcomingCount() + "건"), lines,
                "기한 없는 미완료 할 일 " + digest.getNoDueDateCount() + "건 제외 · 기준일 " + today,
                NotificationMessage.Type.DAILY_DIGEST, today);
    }

    private String line(Task task, LocalDate today) {
        String owner = task.getAssignee() == null ? "담당자 미정"
                : task.getAssignee().getFullName() + "(" + task.getAssignee().getTeam() + ")";
        String bucket = task.getDueDate().isBefore(today) ? "⏰ 마감 지남" : task.getDueDate().equals(today) ? "📌 오늘 마감" : "📅 3일 이내 마감";
        String warning = task.getAssignee() != null || task.getUnresolvedAssignee() == null ? "" : task.getUnresolvedAssignee().warning();
        return (owner + " · " + bucket + " · " + task.getTitle() + " · " + task.getDueDate()
                + (warning.isBlank() ? "" : " · ⚠ " + warning)).replaceAll("[\\r\\n]+", " ");
    }
}
