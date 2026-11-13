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
    public static final String TASK_MENU_ACTION = "meetodo_task_menu";
    public static final String COMPLETE_ACTION = "meetodo_complete_task";
    public static final String ASSIGN_CANDIDATE_ACTION = "meetodo_assign_candidate";
    public static final String ASSIGN_SELECT_ACTION = "meetodo_assign_select";
    public static final String ASSIGN_USER_ACTION = "meetodo_assign_user";
    public static final String ASSIGN_BLOCK_PREFIX = "meetodo_assign_task:";
    public record Rendered(String text, List<Map<String, Object>> blocks) {
        public String blocksJson() { return new GsonBuilder().disableHtmlEscaping().create().toJson(blocks); }
    }

    public Rendered render(NotificationMessage message, List<Task> tasks) { return render(message, tasks, Map.of()); }

    public Rendered render(NotificationMessage message, List<Task> tasks, Map<Long, MeetingTaskResponse> metadata) {
        return render(message, tasks, metadata, null);
    }

    public Rendered renderDirectMessage(NotificationMessage message, List<Task> tasks,
                                         Map<Long, MeetingTaskResponse> metadata, String permalink) {
        return renderDirectMessage(message, tasks, metadata, permalink, null);
    }

    public Rendered renderDirectMessage(NotificationMessage message, List<Task> tasks,
            Map<Long, MeetingTaskResponse> metadata, String permalink, String actor) {
        String title = "📝 새 할 일이 배정됐어요" + (mention(actor).isEmpty() ? "" : " (지정: " + mention(actor) + ")");
        Rendered rendered = render(new NotificationMessage(message.meetingId(), title, List.of(), List.of(), message.footer()),
                tasks, metadata, permalink);
        if (mention(actor).isEmpty()) return rendered;
        String escapedTitle = bounded(escape(title));
        var blocks = new ArrayList<>(rendered.blocks()); blocks.set(0, section(title, null));
        return new Rendered(title + rendered.text().substring(escapedTitle.length()), List.copyOf(blocks));
    }

    public Rendered renderDirectMessage(NotificationMessage message, List<Task> tasks,
            Map<Long, MeetingTaskResponse> metadata, String permalink, String actor,
            SlackMessageReference.NotificationKind kind, String targetUser) {
        if (kind == SlackMessageReference.NotificationKind.ASSIGNMENT) {
            Rendered rendered = renderDirectMessage(message, tasks, metadata, permalink, actor);
            return stripTransferredControls(rendered, tasks, targetUser);
        }
        String title = kind == SlackMessageReference.NotificationKind.HANDOFF
                ? "할 일이 다른 담당자에게 넘어갔어요"
                : "📝 할 일이 배정됐어요" + (mention(actor).isEmpty() ? "" : " (변경: " + mention(actor) + ")");
        Rendered rendered = render(new NotificationMessage(message.meetingId(), title, List.of(), List.of(), message.footer()),
                tasks, metadata, permalink);
        var blocks = new ArrayList<>(rendered.blocks()); blocks.set(0, section(title, null));
        rendered = new Rendered(title + rendered.text().substring(bounded(escape(title)).length()), List.copyOf(blocks));
        return kind == SlackMessageReference.NotificationKind.HANDOFF ? withoutControls(rendered)
                : stripTransferredControls(rendered, tasks, targetUser);
    }

    private Rendered stripTransferredControls(Rendered rendered, List<Task> tasks, String targetUser) {
        if (targetUser == null) return rendered;
        var transferred = tasks.stream().filter(task -> task.getAssignee() == null
                || !targetUser.equals(task.getAssignee().getSlackUserId())).map(Task::getId).toList();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (var original : rendered.blocks()) {
            if (original.get("block_id") instanceof String id && transferred.stream().anyMatch(task -> id.equals(ASSIGN_BLOCK_PREFIX + task))) continue;
            var block = new LinkedHashMap<>(original);
            if (block.get("accessory") instanceof Map<?, ?> accessory && accessory.get("options") instanceof List<?> options
                    && options.stream().anyMatch(option -> option instanceof Map<?, ?> map && transferred.stream()
                        .anyMatch(task -> ("manage:" + task).equals(map.get("value"))))) block.remove("accessory");
            blocks.add(block);
        }
        return new Rendered(rendered.text(), List.copyOf(blocks));
    }

    private Rendered withoutControls(Rendered rendered) {
        List<Map<String,Object>> blocks = new ArrayList<>();
        for (var original : rendered.blocks()) {
            if ("actions".equals(original.get("type"))) continue;
            var block = new LinkedHashMap<>(original); block.remove("accessory"); blocks.add(block);
        }
        return new Rendered(rendered.text(), List.copyOf(blocks));
    }

    List<Map<String, Object>> taskBlocks(Task task) {
        var message = new NotificationMessage(null, "내 할 일", List.of(), List.of(), "MeetoDo");
        List<Map<String, Object>> blocks = new ArrayList<>();
        String line = line(message, task, null);
        blocks.add(taskSection(line.length() > 3000 ? bounded(line, 2999) + "…" : line, task));
        var actions = taskActions(task);
        if (actions != null) blocks.add(actions);
        return List.copyOf(blocks);
    }

    static boolean validPermalink(String value) {
        return value != null && value.length() <= 2048
                && value.matches("https://[A-Za-z0-9.-]+\\.slack\\.com/archives/[A-Z0-9]+/p[0-9]+(?:\\?[A-Za-z0-9_=&.%-]+)?");
    }

    private Rendered render(NotificationMessage message, List<Task> tasks, Map<Long, MeetingTaskResponse> metadata, String permalink) {
        List<Task> ordered = message.digestDate() == null ? tasks : tasks.stream().sorted(Comparator
                .comparing((Task task) -> task.getAssignee() == null ? null : task.getAssignee().getId(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Task::getId)).toList();
        List<String> heading = new ArrayList<>();
        heading.add(bounded(escape(message.title())));
        message.summaryLines().forEach(summary -> heading.add(bounded(escape(summary))));
        String footer = bounded(escape(message.footer()));
        if (validPermalink(permalink)) {
            String link = "\n<" + permalink + "|원래 채널 메시지>";
            footer = bounded(footer, 3000 - link.length()) + link;
        }
        List<String> lines = ordered.stream().map(task -> line(message, task, metadata.get(task.getId()))).toList();
        int shown = 0;
        if (fits(heading, lines, "", footer, ordered)) shown = lines.size();
        else for (int size = 1; size <= lines.size(); size++) {
            String omitted = size == lines.size() ? "" : escape(message.omission(lines.size() - size));
            if (!fits(heading, lines.subList(0, size), omitted, footer, ordered.subList(0, size))) break;
            shown = size;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> text = new ArrayList<>(heading);
        heading.forEach(value -> blocks.add(section(value, null)));
        for (int i = 0; i < shown; i++) {
            Task task = ordered.get(i);
            blocks.add(taskSection(lines.get(i), task));
            Map<String, Object> actions = taskActions(task);
            if (actions != null) blocks.add(actions);
            text.add(lines.get(i));
        }
        if (shown < lines.size()) {
            String omitted = escape(message.omission(lines.size() - shown));
            blocks.add(section(omitted, null)); text.add(omitted);
        }
        blocks.add(section(footer, null)); text.add(footer);
        return new Rendered(String.join("\n", text), List.copyOf(blocks));
    }

    private boolean fits(List<String> heading, List<String> lines, String omitted, String footer, List<Task> tasks) {
        if (heading.size() + lines.size() + tasks.stream().filter(t -> taskActions(t) != null).count() + 1 + (omitted.isEmpty() ? 0 : 1) > 50
                || lines.stream().anyMatch(line -> line.length() > 3000)) return false;
        List<String> text = new ArrayList<>(heading); text.addAll(lines);
        if (!omitted.isEmpty()) text.add(omitted);
        text.add(footer);
        return String.join("\n", text).length() <= 40000;
    }

    private String line(NotificationMessage message, Task task, MeetingTaskResponse detail) {
        if (task.isDeleted()) return "🗑 삭제됨 · " + escape(task.getTitle());
        String owner = task.getAssignee() == null ? "담당자 미정" : mention(task.getAssignee().getSlackUserId());
        if (owner.isEmpty()) owner = escape(task.getAssignee().getFullName() + "(" + task.getAssignee().getTeam() + ")");
        String state = task.getStatus() == TaskStatus.DONE ? "✅ 완료" : "⬜ 미완료";
        String actor = mention(task.getCompletedBySlackUserId());
        if (task.getStatus() == TaskStatus.DONE && !actor.isEmpty()) state += " (" + actor + ")";
        String warning = task.getWarning();
        if (task.getAssignee() == null && detail != null && detail.warning() != null && !detail.warning().isBlank()) {
            warning = detail.warning();
            if (task.getDueDateWarning() != null && !warning.contains(task.getDueDateWarning()))
                warning += " · " + task.getDueDateWarning();
        }
        if ((warning == null || warning.isBlank()) && detail != null && detail.dueDateWarning() != null)
            warning = detail.dueDateWarning();
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

    private Map<String, Object> assignment(Task task) {
        if (task.isDeleted() || task.getStatus() != TaskStatus.TODO || task.getAssignee() != null) return null;
        var snapshot = task.getUnresolvedAssignee();
        var candidates = snapshot == null ? List.<com.meetodo.task.UnresolvedAssignee.Candidate>of() : snapshot.candidates();
        List<Map<String, Object>> elements = new ArrayList<>();
        if (snapshot != null && ("AMBIGUOUS".equals(snapshot.reason()) || "TEAM_MISMATCH".equals(snapshot.reason())) && !candidates.isEmpty()) {
            if (candidates.size() <= 3) for (var candidate : candidates) elements.add(Map.of(
                    "type", "button", "action_id", ASSIGN_CANDIDATE_ACTION + "_" + candidate.id(),
                    "text", Map.of("type", "plain_text", "text", bounded(candidate.name() + "(" + candidate.team() + ")으로 지정", 75)),
                    "value", task.getId() + ":" + candidate.id()));
            else elements.add(Map.of("type", "static_select", "action_id", ASSIGN_SELECT_ACTION,
                    "placeholder", Map.of("type", "plain_text", "text", "담당자 선택"),
                    "options", candidates.stream().limit(20).map(candidate -> Map.of(
                            "text", Map.of("type", "plain_text", "text", bounded(candidate.name() + "(" + candidate.team() + ")", 75)),
                            "value", candidate.id().toString())).toList()));
        } else elements.add(Map.of("type", "users_select", "action_id", ASSIGN_USER_ACTION,
                "placeholder", Map.of("type", "plain_text", "text", "담당자 지정")));
        return Map.of("type", "actions", "block_id", ASSIGN_BLOCK_PREFIX + task.getId(), "elements", elements);
    }

    private Map<String, Object> taskActions(Task task) {
        if (task.isDeleted() || task.getStatus() != TaskStatus.TODO) return null;
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "✅ 완료"),
                "action_id", COMPLETE_ACTION, "value", task.getId().toString()));
        var assignment = assignment(task);
        if (assignment != null) {
            @SuppressWarnings("unchecked") var choices = (List<Map<String, Object>>) assignment.get("elements");
            elements.addAll(choices);
        }
        return Map.of("type", "actions", "block_id", ASSIGN_BLOCK_PREFIX + task.getId(), "elements", elements);
    }

    private Map<String, Object> taskSection(String text, Task task) {
        var block = section(text, null);
        if (task.isDeleted()) return block;
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(menuOption("관리…", "manage", task.getId()));
        options.add(menuOption("제목 수정", "title", task.getId()));
        options.add(menuOption("마감일 수정", "due", task.getId()));
        if (task.getStatus() == TaskStatus.DONE) options.add(menuOption("다시 열기", "reopen", task.getId()));
        options.add(menuOption("삭제", "delete", task.getId()));
        block.put("accessory", Map.of("type", "overflow", "action_id", TASK_MENU_ACTION, "options", options));
        return block;
    }

    private Map<String, Object> menuOption(String label, String operation, Long taskId) {
        return Map.of("text", Map.of("type", "plain_text", "text", label), "value", operation + ":" + taskId);
    }

    private Map<String, Object> section(String text, Long taskId) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "section");
        block.put("text", Map.of("type", "mrkdwn", "text", text.isBlank() ? " " : text, "verbatim", true));
        if (taskId != null) block.put("accessory", Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "✅ 완료"),
                "action_id", COMPLETE_ACTION, "value", taskId.toString()));
        return block;
    }

    private String bounded(String text) { return bounded(text, 3000); }
    private String bounded(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
    }
    static String mention(String id) { return id != null && id.matches("[UW][A-Z0-9]+") ? "<@" + id + ">" : ""; }
    static String escape(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n]+", " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("*", "＊").replace("_", "＿").replace("~", "～").replace("`", "｀");
    }
}
