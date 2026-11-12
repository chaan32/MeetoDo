package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.notification.NotificationMessage;
import com.meetodo.task.Task;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import com.meetodo.task.TaskStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SlackBotRenderer {
    public static final String COMPLETE_ACTION = "meetodo_complete_task";
    public record Rendered(String text, List<Map<String, Object>> blocks) {
        public String blocksJson() { return new GsonBuilder().disableHtmlEscaping().create().toJson(blocks); }
    }

    public Rendered render(NotificationMessage message, List<Task> tasks) { return render(message, tasks, Map.of()); }

    public Rendered render(NotificationMessage message, List<Task> tasks, Map<Long, MeetingTaskResponse> metadata) {
        List<Task> ordered = message.digestDate() == null ? tasks : tasks.stream().sorted(Comparator
                .comparing((Task task) -> task.getAssignee() == null ? null : task.getAssignee().getId(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Task::getId)).toList();
        List<String> heading = new ArrayList<>();
        heading.add(bounded(escape(message.title())));
        message.summaryLines().forEach(summary -> heading.add(bounded(escape(summary))));
        String footer = bounded(escape(message.footer()));
        List<String> lines = ordered.stream().map(task -> line(message, task, metadata.get(task.getId()))).toList();
        int shown = 0;
        if (fits(heading, lines, "", footer)) shown = lines.size();
        else for (int size = 1; size <= lines.size(); size++) {
            String omitted = size == lines.size() ? "" : escape(message.omission(lines.size() - size));
            if (!fits(heading, lines.subList(0, size), omitted, footer)) break;
            shown = size;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> text = new ArrayList<>(heading);
        heading.forEach(value -> blocks.add(section(value, null)));
        for (int i = 0; i < shown; i++) {
            Task task = ordered.get(i);
            blocks.add(section(lines.get(i), task.getStatus() == TaskStatus.TODO ? task.getId() : null));
            text.add(lines.get(i));
        }
        if (shown < lines.size()) {
            String omitted = escape(message.omission(lines.size() - shown));
            blocks.add(section(omitted, null)); text.add(omitted);
        }
        blocks.add(section(footer, null)); text.add(footer);
        return new Rendered(String.join("\n", text), List.copyOf(blocks));
    }

    private boolean fits(List<String> heading, List<String> lines, String omitted, String footer) {
        if (heading.size() + lines.size() + 1 + (omitted.isEmpty() ? 0 : 1) > 50
                || lines.stream().anyMatch(line -> line.length() > 3000)) return false;
        List<String> text = new ArrayList<>(heading); text.addAll(lines);
        if (!omitted.isEmpty()) text.add(omitted);
        text.add(footer);
        return String.join("\n", text).length() <= 40000;
    }

    private String line(NotificationMessage message, Task task, MeetingTaskResponse detail) {
        String owner = task.getAssignee() == null ? "담당자 미정" : mention(task.getAssignee().getSlackUserId());
        if (owner.isEmpty()) owner = escape(task.getAssignee().getFullName() + "(" + task.getAssignee().getTeam() + ")");
        String state = task.getStatus() == TaskStatus.DONE ? "✅ 완료" : "⬜ 미완료";
        String actor = mention(task.getCompletedBySlackUserId());
        if (task.getStatus() == TaskStatus.DONE && !actor.isEmpty()) state += " (" + actor + ")";
        String warning = task.getAssignee() != null ? "" : detail != null ? detail.warning()
                : task.getUnresolvedAssignee() == null ? "" : task.getUnresolvedAssignee().warning();
        String value = state + " · " + owner + " · " + escape(task.getTitle()) + " · "
                + (task.getDueDate() == null ? "마감일 없음" : task.getDueDate());
        if (warning != null && !warning.isBlank()) value += " · ⚠️ " + escape(warning);
        if (detail != null) {
            value = ("EXISTING".equals(detail.resultType()) ? "♻️ " : "🆕 ") + value;
            if (detail.mentionCount() >= 2) value += " · " + detail.mentionCount() + "번째 언급";
        }
        if (message.digestDate() != null && task.getDueDate() != null) value =
                (task.getDueDate().isBefore(message.digestDate()) ? "⏰ 마감 지남" : task.getDueDate().equals(message.digestDate()) ? "📌 오늘 마감" : "📅 3일 이내 마감") + " · " + value;
        return value;
    }

    private Map<String, Object> section(String text, Long taskId) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "section");
        block.put("text", Map.of("type", "mrkdwn", "text", text.isBlank() ? " " : text, "verbatim", true));
        if (taskId != null) block.put("accessory", Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "✅ 완료"),
                "action_id", COMPLETE_ACTION, "value", taskId.toString()));
        return block;
    }

    private String bounded(String text) {
        if (text.length() <= 3000) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(2999)) ? 2999 : 3000);
    }
    static String mention(String id) { return id != null && id.matches("[UW][A-Z0-9]+") ? "<@" + id + ">" : ""; }
    static String escape(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]+", " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("*", "＊").replace("_", "＿").replace("~", "～").replace("`", "｀");
    }
}
