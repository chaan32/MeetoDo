package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.task.TaskMutationException;
import com.meetodo.task.TaskService;
import com.meetodo.task.dto.TaskResponse;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.request.views.ViewsUpdateRequest;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackTaskEditService {
    static final String MANAGE_ACTION = "meetodo_manage_task_operation";
    static final String CALLBACK = "meetodo_task_edit";
    static final String BLOCK = "task_value";
    static final String ACTION = "value";
    private static final Logger log = LoggerFactory.getLogger(SlackTaskEditService.class);
    private final MethodsClient client;
    private final SlackBotService bot;
    private final TaskService tasks;
    private final Clock clock;
    private SlackHomeService homes;
    private AdminAuthorization admins = new AdminAuthorization("");
    private MemberRepository members;
    void setManagement(AdminAuthorization admins, MemberRepository members) { this.admins = admins; this.members = members; }
    void setHomes(SlackHomeService homes) { this.homes = homes; }
    // Task IDs and origin coordinates are server-owned; modal metadata never authorizes mutations.
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(long taskId, String operation, String channel, String timestamp, String actor, Instant expiresAt, boolean home) { }

    public SlackTaskEditService(MethodsClient client, SlackBotService bot, TaskService tasks, Clock clock) {
        this.client = client; this.bot = bot; this.tasks = tasks; this.clock = clock;
    }

    public void register(App app) {
        app.blockAction(SlackBotRenderer.TASK_MENU_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var option = payload.getActions().getFirst().getSelectedOption();
                if (payload.getView() != null && "home".equals(payload.getView().getType()) && "view".equals(payload.getContainer().getType()))
                    selectHome(payload.getTriggerId(), payload.getUser().getId(), option == null ? null : option.getValue());
                else select(payload.getTriggerId(), payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), option == null ? null : option.getValue());
            }
            return ack;
        });
        app.blockAction(MANAGE_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var option = payload.getActions().getFirst().getSelectedOption();
                manage(payload.getView().getId(), payload.getUser().getId(), option == null ? null : option.getValue());
            }
            return ack;
        });
        app.viewSubmission(CALLBACK, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) {
                var view = payload.getView(); String value = null; boolean provided = false;
                if (view.getState() != null && view.getState().getValues() != null) {
                    var block = view.getState().getValues().get(BLOCK);
                    if (block != null && block.get(ACTION) != null) {
                        var field = block.get(ACTION); provided = true;
                        synchronized (pending) {
                            Pending origin = pending.get(view.getId());
                            if (origin != null && "reassign".equals(origin.operation()) && !"static_select".equals(field.getType())) provided = false;
                        }
                        value = "datepicker".equals(field.getType()) ? field.getSelectedDate() : field.getValue();
                        if ("static_select".equals(field.getType()))
                            value = field.getSelectedOption() == null ? null : field.getSelectedOption().getValue();
                    }
                }
                submit(view.getId(), payload.getUser().getId(), provided, value);
            }
            return ack;
        });
        app.viewClosed(CALLBACK, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) close(payload.getView().getId(), payload.getUser().getId());
            return ack;
        });
    }

    public void select(String trigger, String channel, String timestamp, String actor, String selection) {
        select(trigger, channel, timestamp, actor, selection, false);
    }

    public void selectHome(String trigger, String actor, String selection) {
        select(trigger, null, null, actor, selection, true);
    }

    private void select(String trigger, String channel, String timestamp, String actor, String selection, boolean home) {
        try {
            String[] parts = selection.split(":", -1);
            if (parts.length != 2 || !List.of("manage", "reassign", "title", "due", "reopen", "delete").contains(parts[0])) throw new IllegalArgumentException();
            long taskId = Long.parseLong(parts[1]);
            if (home) homes.authorizeTask(actor, taskId);
            else bot.authorizeTaskMessage(channel, timestamp, actor, taskId);
            TaskResponse task = tasks.authorizeMutation(taskId, actor);
            if ("reassign".equals(parts[0])) authorizeReassignment(actor, task);
            if ("reopen".equals(parts[0])) { tasks.reopen(taskId, actor); return; }
            if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException();
            synchronized (pending) { expire(); if (pending.size() >= 1000) throw new IllegalStateException(); }
            var response = client.viewsOpen(ViewsOpenRequest.builder().triggerId(trigger).viewAsString(
                    new GsonBuilder().disableHtmlEscaping().create().toJson("manage".equals(parts[0]) ? managementModal(task, actor) : modal(parts[0], task))).build());
            if (!response.isOk() || response.getView() == null || response.getView().getId() == null) throw new IllegalStateException();
            synchronized (pending) {
                expire();
                if (pending.size() >= 1000) throw new IllegalStateException();
                pending.put(response.getView().getId(), new Pending(taskId, parts[0], channel, timestamp, actor, clock.instant().plusSeconds(1800), home));
            }
        } catch (Exception failure) { failure(channel, actor, failure); }
    }

    public void submit(String viewId, String actor, boolean provided, String value) {
        Pending origin;
        synchronized (pending) {
            expire(); origin = pending.get(viewId);
            if (origin != null && origin.actor().equals(actor)) pending.remove(viewId);
            else origin = null;
        }
        if (origin == null) return;
        try {
            if (origin.home()) homes.authorizeTask(actor, origin.taskId());
            else bot.authorizeTaskMessage(origin.channel(), origin.timestamp(), actor, origin.taskId());
            TaskResponse task = tasks.authorizeMutation(origin.taskId(), actor);
            switch (origin.operation()) {
                case "reassign" -> {
                    authorizeReassignment(actor, task);
                    if (!provided || value == null) throw new IllegalArgumentException();
                    long memberId = Long.parseLong(value);
                    if (members.findById(memberId).filter(com.meetodo.member.Member::isActive).isEmpty()) throw new IllegalArgumentException();
                    tasks.assignOrReassign(origin.taskId(), memberId, actor);
                }
                case "title" -> {
                    if (!provided || value == null || value.isBlank() || value.length() > 200) throw new IllegalArgumentException();
                    tasks.edit(origin.taskId(), value, false, null, actor);
                }
                case "due" -> {
                    if (!provided) throw new IllegalArgumentException();
                    LocalDate date = value == null ? null : LocalDate.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));
                    tasks.edit(origin.taskId(), null, true, date, actor);
                }
                case "delete" -> tasks.delete(origin.taskId(), actor);
                default -> throw new IllegalArgumentException();
            }
        } catch (Exception failure) { failure(origin.channel(), actor, failure); }
    }

    public void manage(String viewId, String actor, String operation) {
        Pending origin;
        synchronized (pending) { expire(); origin = pending.get(viewId); }
        if (origin == null || !origin.actor().equals(actor) || !"manage".equals(origin.operation())) return;
        try {
            if (!List.of("title", "due", "delete", "reopen", "reassign").contains(operation)) throw new IllegalArgumentException();
            if (origin.home()) homes.authorizeTask(actor, origin.taskId());
            else bot.authorizeTaskMessage(origin.channel(), origin.timestamp(), actor, origin.taskId());
            TaskResponse task = tasks.authorizeMutation(origin.taskId(), actor);
            if ("reassign".equals(operation)) authorizeReassignment(actor, task);
            if ("reopen".equals(operation)) {
                tasks.reopen(origin.taskId(), actor);
                // Keep the modal private and remove any stale choices after the mutation.
                var view = Map.of("type", "modal", "callback_id", CALLBACK, "title", plain("할 일 관리"),
                        "close", plain("닫기"), "blocks", List.of(Map.of("type", "section", "text", plain("할 일을 다시 열었어요."))));
                client.viewsUpdate(ViewsUpdateRequest.builder().viewId(viewId).viewAsString(new GsonBuilder().create().toJson(view)).build());
                close(viewId, actor); return;
            }
            var response = client.viewsUpdate(ViewsUpdateRequest.builder().viewId(viewId)
                    .viewAsString(new GsonBuilder().disableHtmlEscaping().create().toJson(modal(operation, task))).build());
            if (!response.isOk()) throw new IllegalStateException();
            synchronized (pending) {
                if (origin.equals(pending.get(viewId))) pending.put(viewId, new Pending(origin.taskId(), operation,
                        origin.channel(), origin.timestamp(), actor, origin.expiresAt(), origin.home()));
            }
        } catch (Exception failure) { failure(origin.channel(), actor, failure); }
    }

    private void authorizeReassignment(String actor, TaskResponse task) {
        admins.requireAdmin(actor);
        if (task.deleted() || task.status() != TaskStatus.TODO) throw new IllegalArgumentException();
    }

    private Map<String,Object> managementModal(TaskResponse task, String actor) {
        var options = new java.util.ArrayList<Map<String,Object>>();
        options.add(operationOption("제목 수정", "title"));
        options.add(operationOption("마감일 수정", "due"));
        if (task.status() == TaskStatus.DONE) options.add(operationOption("다시 열기", "reopen"));
        options.add(operationOption("삭제", "delete"));
        if (admins.isAdmin(actor) && task.status() == TaskStatus.TODO) options.add(operationOption("담당자 변경", "reassign"));
        return Map.of("type", "modal", "callback_id", CALLBACK, "notify_on_close", true,
                "title", plain("할 일 관리"), "close", plain("닫기"), "blocks", List.of(
                    Map.of("type", "section", "text", plain(task.title())),
                    Map.of("type", "actions", "elements", List.of(Map.of("type", "static_select", "action_id", MANAGE_ACTION,
                            "placeholder", plain("관리 작업 선택"), "options", options)))));
    }
    private Map<String,Object> operationOption(String label, String operation) { return Map.of("text", plain(label), "value", operation); }

    public void close(String viewId, String actor) {
        synchronized (pending) {
            Pending origin = pending.get(viewId);
            if (origin != null && origin.actor().equals(actor)) pending.remove(viewId);
        }
    }

    private Map<String,Object> modal(String operation, TaskResponse task) {
        Map<String,Object> element = new HashMap<>();
        List<Map<String,Object>> blocks;
        if ("reassign".equals(operation)) {
            var active = members.findAll().stream().filter(com.meetodo.member.Member::isActive).toList();
            // shortcut: static_select supports 100 options; use external_select when active membership exceeds that limit.
            if (active.isEmpty() || active.size() > 100) throw new IllegalArgumentException();
            blocks = List.of(Map.of("type", "input", "block_id", BLOCK, "label", plain("활성 팀원"), "element",
                    Map.of("type", "static_select", "action_id", ACTION, "placeholder", plain("새 담당자 선택"), "options",
                        active.stream().map(member -> Map.of("text", plain(bounded(member.getFullName() + "(" + member.getTeam() + ")")), "value", member.getId().toString())).toList())));
        }
        else if ("delete".equals(operation)) blocks = List.of(Map.of("type", "section", "text", plain("이 할 일을 삭제할까요? " + task.title())));
        else {
            element.put("type", "title".equals(operation) ? "plain_text_input" : "datepicker"); element.put("action_id", ACTION);
            if ("title".equals(operation)) { element.put("initial_value", task.title()); element.put("min_length", 1); element.put("max_length", 200); }
            else if (task.dueDate() != null) element.put("initial_date", task.dueDate().toString());
            blocks = List.of(Map.of("type", "input", "block_id", BLOCK, "optional", "due".equals(operation),
                    "label", plain("title".equals(operation) ? "제목" : "마감일 (선택 해제하면 기한 없음)"), "element", element));
        }
        return Map.of("type", "modal", "callback_id", CALLBACK, "notify_on_close", true,
                "title", plain("delete".equals(operation) ? "할 일 삭제 확인" : "reassign".equals(operation) ? "담당자 변경" : "할 일 수정"),
                "submit", plain("delete".equals(operation) ? "삭제" : "저장"), "close", plain("취소"), "blocks", blocks);
    }

    private void failure(String channel, String actor, Exception failure) {
        String text = failure instanceof com.meetodo.task.TaskCompletionForbiddenException ? "담당자만 완료할 수 있어요"
                : failure instanceof com.meetodo.task.TaskAssignmentException ? failure.getMessage()
                : failure instanceof TaskMutationException ? failure.getMessage()
                : failure instanceof com.meetodo.task.exception.DuplicateTaskException duplicate ? "이미 같은 할 일이 있어요 (#" + duplicate.getExistingTaskId() + ")"
                : "할 일을 변경하지 못했어요. 메시지에서 다시 시도해 주세요.";
        log.warn("Slack task mutation failed: {}", failure instanceof TaskMutationException mutation ? mutation.getCode() : "INTERNAL_ERROR");
        if (channel == null && homes != null) homes.publish(actor, text);
        else bot.ephemeral(channel, actor, text);
    }
    private String bounded(String text) { return text.length() <= 75 ? text : text.substring(0, Character.isHighSurrogate(text.charAt(74)) ? 74 : 75); }
    private Map<String,String> plain(String text) { return Map.of("type", "plain_text", "text", text); }
    private void expire() { pending.values().removeIf(origin -> !clock.instant().isBefore(origin.expiresAt())); }
}
