package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.notification.NotificationStateService;
import com.meetodo.task.*;
import com.meetodo.task.exception.DuplicateTaskException;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsPublishRequest;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackHomeService {
    public static final String MANAGE_MEMBERS_ACTION = "meetodo_manage_members";
    private static final Logger log = LoggerFactory.getLogger(SlackHomeService.class);
    private final MethodsClient client;
    private final MemberRepository members;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final AdminAuthorization admins;
    private final Clock clock;
    private final SlackBotRenderer renderer = new SlackBotRenderer();
    // shortcut: opened Home subscriptions live in this process; use shared storage when running multiple instances.
    private final Set<String> opened = ConcurrentHashMap.newKeySet();

    public SlackHomeService(MethodsClient client, MemberRepository members, TaskRepository tasks,
                            TaskService taskService, AdminAuthorization admins, Clock clock) {
        this.client = client; this.members = members; this.tasks = tasks;
        this.taskService = taskService; this.admins = admins; this.clock = clock;
    }

    public void publish(String user) {
        if (SlackBotRenderer.mention(user).isEmpty()) return;
        opened.add(user);
        publish(user, null);
    }

    public void publish(String user, String notice) {
        if (SlackBotRenderer.mention(user).isEmpty() || !opened.contains(user)) return;
        try {
            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(section("📝 MeetoDo · 내 할 일"));
            if (notice != null && !notice.isBlank()) blocks.add(section(SlackBotRenderer.escape(notice)));
            if (admins.isAdmin(user)) blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
                    "type", "button", "action_id", MANAGE_MEMBERS_ACTION,
                    "text", Map.of("type", "plain_text", "text", "팀원 관리"), "value", "members"))));
            Member member = members.findBySlackUserIdAndActiveTrue(user).orElse(null);
            if (member == null) blocks.add(section("팀원으로 등록되지 않았어요. 관리자에게 요청해 주세요."));
            else renderTasks(blocks, member, user);
            String view = new GsonBuilder().disableHtmlEscaping().create().toJson(Map.of("type", "home", "blocks", blocks));
            var response = client.viewsPublish(ViewsPublishRequest.builder().userId(user).viewAsString(view).build());
            if (!response.isOk()) log.warn("Slack Home publish failed: {}", NotificationStateService.safeCode(response.getError()));
        } catch (Exception ignored) { log.warn("Slack Home publish failed: INTERNAL_ERROR"); }
    }

    private void renderTasks(List<Map<String, Object>> blocks, Member member, String user) {
        List<Task> owned = tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO).stream()
                .filter(this::visible).filter(task -> task.getAssignee() != null && user.equals(task.getAssignee().getSlackUserId())).toList();
        List<Task> unassigned = tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO).stream()
                .filter(this::visible).filter(task -> task.getAssignee() == null).toList();
        int shown = appendTasks(blocks, SlackBotRenderer.escape(member.getFullName()) + "님의 할 일", owned);
        shown += appendTasks(blocks, "📋 담당자 미정 할 일", unassigned);
        int omitted = owned.size() + unassigned.size() - shown;
        if (omitted > 0) blocks.add(section("외 " + omitted + "건은 GET /api/tasks에서 확인"));
        else if (owned.isEmpty() && unassigned.isEmpty()) blocks.add(section("미완료 할 일이 없어요."));
    }

    private int appendTasks(List<Map<String, Object>> blocks, String heading, List<Task> source) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        LocalDate sunday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        List<Task> ordered = source.stream().sorted(Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Task::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        String[] labels = {"⏰ 마감 지남", "📌 오늘 마감", "📅 이번 주", "🗓 이후", "마감일 없음"};
        int shown = 0, previous = -1;
        for (Task task : ordered) {
            LocalDate date = task.getDueDate();
            int bucket = date == null ? 4 : date.isBefore(today) ? 0 : date.equals(today) ? 1 : !date.isAfter(sunday) ? 2 : 3;
            List<Map<String, Object>> rendered = renderer.taskBlocks(task);
            int headers = (shown == 0 ? 1 : 0) + (previous != bucket ? 1 : 0);
            // Reserve the final block for an accurate omission notice.
            if (blocks.size() + headers + rendered.size() > 99) break;
            if (shown == 0) blocks.add(section(heading));
            if (previous != bucket) blocks.add(section(labels[bucket]));
            blocks.addAll(rendered); previous = bucket; shown++;
        }
        return shown;
    }

    private boolean visible(Task task) { return !task.isDeleted() && task.getStatus() == TaskStatus.TODO; }

    public void refreshTask(Long id, boolean assignment) {
        try {
            Task task = current(id);
            if (assignment || task.getAssignee() == null) refreshAll();
            else {
                String user = task.getAssignee().getSlackUserId();
                if (user != null && opened.contains(user)) publish(user, null);
            }
        } catch (Exception ignored) { log.warn("Slack Home refresh failed: INTERNAL_ERROR"); }
    }

    public void refreshAll() { for (String user : opened) publish(user, null); }

    void authorizeTask(String user, long taskId) {
        if (SlackBotRenderer.mention(user).isEmpty() || !opened.contains(user)
                || members.findBySlackUserIdAndActiveTrue(user).isEmpty()) throw new IllegalArgumentException();
        Task task = current(taskId);
        if (task.isDeleted() || task.getAssignee() != null && !user.equals(task.getAssignee().getSlackUserId()) && !admins.isAdmin(user))
            throw new IllegalArgumentException();
    }

    private Task current(Long id) {
        if (id == null || id <= 0) throw new IllegalArgumentException();
        return tasks.findAllWithAssigneeByIdIn(List.of(id)).stream().filter(task -> id.equals(task.getId())).findFirst().orElseThrow();
    }

    public void complete(String user, String taskValue) {
        try {
            long id = Long.parseLong(taskValue); authorizeTask(user, id);
            taskService.completeFromSlack(id, user); publish(user, null);
        } catch (Exception exception) { failure(user, exception); }
    }

    public void assignCandidate(String user, String value) {
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            assign(user, Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception exception) { failure(user, exception); }
    }

    public void assignSelection(String user, String blockId, String memberValue) {
        try { assign(user, assignmentTaskId(blockId), Long.parseLong(memberValue)); }
        catch (Exception exception) { failure(user, exception); }
    }

    public void assignUser(String user, String blockId, String selectedUser) {
        try {
            long id = assignmentTaskId(blockId); authorizeTask(user, id);
            Task current = current(id);
            if (current.getAssignee() != null || current.getStatus() == TaskStatus.DONE) {
                publish(user, null); return;
            }
            if (SlackBotRenderer.mention(selectedUser).isEmpty()) throw new IllegalArgumentException();
            Member target = members.findBySlackUserIdAndActiveTrue(selectedUser).orElseThrow(() ->
                    new TaskAssignmentException("MEMBER_NOT_FOUND", "등록된 팀원이 아니에요"));
            taskService.assign(id, target.getId(), user); publish(user, null);
        } catch (Exception exception) { failure(user, exception); }
    }

    private void assign(String user, long id, long memberId) {
        authorizeTask(user, id); taskService.assign(id, memberId, user); publish(user, null);
    }

    private long assignmentTaskId(String blockId) {
        if (blockId == null || !blockId.startsWith(SlackBotRenderer.ASSIGN_BLOCK_PREFIX)) throw new IllegalArgumentException();
        return Long.parseLong(blockId.substring(SlackBotRenderer.ASSIGN_BLOCK_PREFIX.length()));
    }

    private void failure(String user, Exception exception) {
        String notice = "할 일을 변경하지 못했어요. Home에서 다시 시도해 주세요.";
        if (exception instanceof TaskAssignmentException assignment) notice = assignment.getMessage();
        else if (exception instanceof DuplicateTaskException duplicate) notice = "이미 같은 할 일이 있어요 (#" + duplicate.getExistingTaskId() + ")";
        else if (exception instanceof TaskCompletionForbiddenException) notice = "담당자만 완료할 수 있어요";
        publish(user, notice);
    }

    private Map<String, Object> section(String text) {
        String bounded = text.length() > 3000 ? text.substring(0, Character.isHighSurrogate(text.charAt(2999)) ? 2999 : 3000) : text;
        return Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", bounded, "verbatim", true));
    }
}
