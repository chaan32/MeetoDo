package com.meetodo.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingResults;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import com.meetodo.notification.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

public class SlackBotService {
    private static final Logger log = LoggerFactory.getLogger(SlackBotService.class);
    private boolean dmEnabled;
    public void setDmEnabled(boolean enabled) { this.dmEnabled = enabled; }

    private final MethodsClient client;
    private final String channel;
    private final SlackMessageReferenceRepository references;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final MeetingResults results;
    private final MeetingRepository meetings;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final SlackBotRenderer renderer = new SlackBotRenderer();
    private final NotificationMessageFactory messages = new NotificationMessageFactory();

    public SlackBotService(MethodsClient client, String channel, SlackMessageReferenceRepository references,
                            TaskRepository tasks, TaskService taskService, MeetingResults results,
                            MeetingRepository meetings, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.client = client; this.channel = channel; this.references = references; this.tasks = tasks;
        this.taskService = taskService; this.results = results; this.meetings = meetings;
        this.mapper = mapper; this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void send(NotificationMessage supplied) {
        try {
            NotificationMessage message = supplied.meetingId() == null ? supplied : messages.from(results.get(supplied.meetingId()));
            List<Task> members = message.meetingId() != null
                    ? tasks.findAllWithAssigneeByIdIn(results.get(message.meetingId()).tasks().stream().map(t -> t.taskId()).toList())
                    : tasks.findDigestTasks(TaskStatus.TODO, Objects.requireNonNull(message.digestDate()).plusDays(3));
            if (message.meetingId() == null) message = digest(message.digestDate(), members);
            if (message.meetingId() != null && results.get(message.meetingId()).status() == com.meetodo.meeting.MeetingStatus.FAILED) members = List.of();
            var rendered = render(message, members);
            var response = client.chatPostMessage(post(channel, rendered));
            if (!response.isOk()) throw delivery(response.getError());
            if (response.getTs() == null || response.getChannel() == null) throw delivery(null);
            String taskIds = taskIds(members);
            NotificationMessage posted = message;
            transactions.executeWithoutResult(status -> {
                var meeting = posted.meetingId() == null ? null : meetings.findById(posted.meetingId()).orElseThrow();
                if (meeting != null) meeting.recordSlackMessage(response.getChannel(), response.getTs());
                references.save(new SlackMessageReference(response.getChannel(), response.getTs(), meeting, posted.digestDate(), taskIds));
            });
            if (dmEnabled && message.meetingId() != null) {
                try { sendDirectMessages(message, members, response.getChannel(), response.getTs()); }
                catch (Exception ignored) { log.warn("Slack DM processing failed: INTERNAL_ERROR"); }
            }
        } catch (NotificationDeliveryException exception) { throw exception; }
        catch (Exception exception) { throw delivery(null); }
    }

    private void sendDirectMessages(NotificationMessage message, List<Task> members, String channelId, String messageTs) {
        Map<Long, MeetingTaskResponse> details = metadata(message);
        Map<String,List<Task>> recipients = members.stream()
                .filter(task -> details.containsKey(task.getId()) && "CREATED".equals(details.get(task.getId()).resultType()))
                .filter(task -> task.getAssignee() != null && !SlackBotRenderer.mention(task.getAssignee().getSlackUserId()).isEmpty())
                .collect(Collectors.groupingBy(task -> task.getAssignee().getSlackUserId(), LinkedHashMap::new, Collectors.toList()));
        if (recipients.isEmpty()) return;
        String permalink = null;
        String linkError = null;
        try {
            var response = client.chatGetPermalink(ChatGetPermalinkRequest.builder().channel(channelId).messageTs(messageTs).build());
            if (!response.isOk()) throw delivery(response.getError());
            if (!SlackBotRenderer.validPermalink(response.getPermalink())) throw delivery(null);
            permalink = response.getPermalink();
        } catch (Exception exception) { linkError = errorCode(exception); }
        for (var recipient : recipients.entrySet()) {
            String dmChannel = null;
            String dmTs = null;
            String error = linkError;
            if (error == null) {
                try {
                    var opened = client.conversationsOpen(ConversationsOpenRequest.builder().users(List.of(recipient.getKey())).build());
                    if (!opened.isOk()) throw delivery(opened.getError());
                    if (opened.getChannel() == null || opened.getChannel().getId() == null) throw delivery(null);
                    dmChannel = opened.getChannel().getId();
                    var rendered = renderer.renderDirectMessage(message, recipient.getValue(), details, permalink);
                    var response = client.chatPostMessage(post(dmChannel, rendered));
                    if (!response.isOk()) throw delivery(response.getError());
                    if (response.getTs() == null || response.getChannel() == null) throw delivery(null);
                    dmChannel = response.getChannel(); dmTs = response.getTs();
                } catch (Exception exception) { error = errorCode(exception); }
            }
            try {
                String storedChannel = dmChannel, storedTs = dmTs, storedError = error, storedLink = permalink;
                String ids = taskIds(recipient.getValue());
                transactions.executeWithoutResult(status -> references.save(SlackMessageReference.dm(storedChannel, storedTs,
                        meetings.findById(message.meetingId()).orElseThrow(), ids, recipient.getKey(),
                        storedError == null ? SlackMessageReference.DeliveryStatus.SENT : SlackMessageReference.DeliveryStatus.FAILED,
                        storedError, storedLink)));
                if (error != null) log.warn("Slack DM delivery failed: {}", error);
            } catch (Exception ignored) { log.warn("Slack DM delivery record failed: INTERNAL_ERROR"); }
        }
    }

    public void complete(String channelId, String messageTs, String actor, String value) {
        if (channelId == null || messageTs == null || SlackBotRenderer.mention(actor).isEmpty()) return;
        SlackMessageReference reference;
        long taskId;
        try {
            taskId = Long.parseLong(value);
            reference = references.findByChannelIdAndMessageTs(channelId, messageTs).orElseThrow();
            if (reference.getDeliveryStatus() != SlackMessageReference.DeliveryStatus.SENT || !ids(reference).contains(taskId)) throw new IllegalArgumentException();
            taskService.completeFromSlack(taskId, actor);
        } catch (TaskCompletionForbiddenException exception) {
            ephemeral(channelId, actor, "담당자만 완료할 수 있어요"); return;
        } catch (Exception exception) {
            ephemeral(channelId, actor, "등록된 메시지와 할 일을 확인할 수 없어요."); return;
        }
        List<SlackMessageReference> related = new ArrayList<>();
        try {
            // shortcut: scan stored membership for this small app; index a task-message relation when message history grows.
            related.addAll(references.findAll().stream().filter(ref -> ref.getDeliveryStatus() == SlackMessageReference.DeliveryStatus.SENT).toList());
        } catch (Exception ignored) { log.warn("Slack message lookup failed: INTERNAL_ERROR"); }
        if (related.stream().noneMatch(ref -> Objects.equals(ref.getChannelId(), channelId) && Objects.equals(ref.getMessageTs(), messageTs))) related.add(reference);
        for (SlackMessageReference ref : related) {
            try {
                List<Long> ids = ids(ref);
                if (!ids.contains(taskId)) continue;
                List<Task> current = tasks.findAllWithAssigneeByIdIn(ids);
                NotificationMessage message = ref.getMeetingId() == null ? digest(ref.getDigestDate(), current) : messages.from(results.get(ref.getMeetingId()));
                var rendered = ref.getMessageType() == SlackMessageReference.MessageType.DM
                        ? renderer.renderDirectMessage(message, current, metadata(message), ref.getPermalink()) : render(message, current);
                var response = client.chatUpdate(ChatUpdateRequest.builder().channel(ref.getChannelId()).ts(ref.getMessageTs())
                        .text(rendered.text()).blocksAsString(rendered.blocksJson()).parse("none").linkNames(false).build());
                if (!response.isOk()) throw delivery(response.getError());
            } catch (Exception exception) {
                log.warn("Slack message refresh failed: {}", errorCode(exception));
            }
        }
    }

    private List<Long> ids(SlackMessageReference reference) throws java.io.IOException {
        return mapper.readValue(reference.getTaskIds(), new TypeReference<>() { });
    }
    private String taskIds(List<Task> members) throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.writeValueAsString(members.stream().map(Task::getId).toList());
    }
    private ChatPostMessageRequest post(String destination, SlackBotRenderer.Rendered rendered) {
        return ChatPostMessageRequest.builder().channel(destination).text(rendered.text()).blocksAsString(rendered.blocksJson())
                .parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).build();
    }
    private NotificationDeliveryException delivery(String code) { return new NotificationDeliveryException(NotificationStateService.safeCode(code)); }
    private String errorCode(Exception exception) {
        return exception instanceof NotificationDeliveryException delivery ? NotificationStateService.safeCode(delivery.getCode()) : "INTERNAL_ERROR";
    }
    private Map<Long, MeetingTaskResponse> metadata(NotificationMessage message) {
        return message.meetingId() == null ? Map.of() : results.get(message.meetingId()).tasks().stream().collect(Collectors.toMap(t -> t.taskId(), t -> t));
    }
    private SlackBotRenderer.Rendered render(NotificationMessage message, List<Task> current) {
        return renderer.render(message, current, metadata(message));
    }

    private NotificationMessage digest(LocalDate date, List<Task> current) {
        List<Task> todo = current.stream().filter(t -> t.getStatus() == TaskStatus.TODO).toList();
        return new NotificationMessage(null, "📋 마감 할 일 알림 (" + date + ")", List.of(
                "⏰ 마감 지남 " + todo.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(date)).count() + "건",
                "📌 오늘 마감 " + todo.stream().filter(t -> date.equals(t.getDueDate())).count() + "건",
                "📅 3일 이내 마감 " + todo.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isAfter(date)).count() + "건"), List.of(),
                "기한 없는 미완료 할 일 " + tasks.countByStatusAndDueDateIsNull(TaskStatus.TODO) + "건 제외 · 기준일 " + date,
                NotificationMessage.Type.DAILY_DIGEST, date);
    }

    private void ephemeral(String channelId, String user, String text) {
        try { client.chatPostEphemeral(ChatPostEphemeralRequest.builder().channel(channelId).user(user).text(text).build()); }
        catch (Exception ignored) { }
    }
}
