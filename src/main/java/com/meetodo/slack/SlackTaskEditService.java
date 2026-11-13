package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.task.TaskMutationException;
import com.meetodo.task.TaskService;
import com.meetodo.task.dto.TaskResponse;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
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
    static final String CALLBACK = "meetodo_task_edit";
    static final String BLOCK = "task_value";
    static final String ACTION = "value";
    private static final Logger log = LoggerFactory.getLogger(SlackTaskEditService.class);
    private final MethodsClient client;
    private final SlackBotService bot;
    private final TaskService tasks;
    private final Clock clock;
    // Task IDs and origin coordinates are server-owned; modal metadata never authorizes mutations.
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(long taskId, String operation, String channel, String timestamp, String actor, Instant expiresAt) { }

    public SlackTaskEditService(MethodsClient client, SlackBotService bot, TaskService tasks, Clock clock) {
        this.client = client; this.bot = bot; this.tasks = tasks; this.clock = clock;
    }

    public void register(App app) {
        app.blockAction(SlackBotRenderer.TASK_MENU_ACTION, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getContainer() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var option = payload.getActions().getFirst().getSelectedOption();
                select(payload.getTriggerId(), payload.getContainer().getChannelId(), payload.getContainer().getMessageTs(),
                        payload.getUser().getId(), option == null ? null : option.getValue());
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
                        value = "datepicker".equals(field.getType()) ? field.getSelectedDate() : field.getValue();
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
        try {
            String[] parts = selection.split(":", -1);
            if (parts.length != 2 || !List.of("title", "due", "reopen", "delete").contains(parts[0])) throw new IllegalArgumentException();
            long taskId = Long.parseLong(parts[1]);
            bot.authorizeTaskMessage(channel, timestamp, actor, taskId);
            TaskResponse task = tasks.authorizeMutation(taskId, actor);
            if ("reopen".equals(parts[0])) { tasks.reopen(taskId, actor); return; }
            if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException();
            synchronized (pending) { expire(); if (pending.size() >= 1000) throw new IllegalStateException(); }
            var response = client.viewsOpen(ViewsOpenRequest.builder().triggerId(trigger).viewAsString(
                    new GsonBuilder().disableHtmlEscaping().create().toJson(modal(parts[0], task))).build());
            if (!response.isOk() || response.getView() == null || response.getView().getId() == null) throw new IllegalStateException();
            synchronized (pending) {
                expire();
                if (pending.size() >= 1000) throw new IllegalStateException();
                pending.put(response.getView().getId(), new Pending(taskId, parts[0], channel, timestamp, actor, clock.instant().plusSeconds(1800)));
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
            bot.authorizeTaskMessage(origin.channel(), origin.timestamp(), actor, origin.taskId());
            tasks.authorizeMutation(origin.taskId(), actor);
            switch (origin.operation()) {
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

    public void close(String viewId, String actor) {
        synchronized (pending) {
            Pending origin = pending.get(viewId);
            if (origin != null && origin.actor().equals(actor)) pending.remove(viewId);
        }
    }

    private Map<String,Object> modal(String operation, TaskResponse task) {
        Map<String,Object> element = new HashMap<>();
        List<Map<String,Object>> blocks;
        if ("delete".equals(operation)) blocks = List.of(Map.of("type", "section", "text", plain("이 할 일을 삭제할까요? " + task.title())));
        else {
            element.put("type", "title".equals(operation) ? "plain_text_input" : "datepicker"); element.put("action_id", ACTION);
            if ("title".equals(operation)) { element.put("initial_value", task.title()); element.put("min_length", 1); element.put("max_length", 200); }
            else if (task.dueDate() != null) element.put("initial_date", task.dueDate().toString());
            blocks = List.of(Map.of("type", "input", "block_id", BLOCK, "optional", "due".equals(operation),
                    "label", plain("title".equals(operation) ? "제목" : "마감일 (선택 해제하면 기한 없음)"), "element", element));
        }
        return Map.of("type", "modal", "callback_id", CALLBACK, "notify_on_close", true,
                "title", plain("delete".equals(operation) ? "할 일 삭제 확인" : "할 일 수정"),
                "submit", plain("delete".equals(operation) ? "삭제" : "저장"), "close", plain("취소"), "blocks", blocks);
    }

    private void failure(String channel, String actor, Exception failure) {
        String text = failure instanceof TaskMutationException ? failure.getMessage()
                : failure instanceof com.meetodo.task.exception.DuplicateTaskException duplicate ? "이미 같은 할 일이 있어요 (#" + duplicate.getExistingTaskId() + ")"
                : "할 일을 변경하지 못했어요. 메시지에서 다시 시도해 주세요.";
        log.warn("Slack task mutation failed: {}", failure instanceof TaskMutationException mutation ? mutation.getCode() : "INTERNAL_ERROR");
        bot.ephemeral(channel, actor, text);
    }
    private Map<String,String> plain(String text) { return Map.of("type", "plain_text", "text", text); }
    private void expire() { pending.values().removeIf(origin -> !clock.instant().isBefore(origin.expiresAt())); }
}
