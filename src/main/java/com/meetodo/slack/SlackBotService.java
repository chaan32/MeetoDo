package com.meetodo.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingResults;
import com.meetodo.notification.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import java.time.LocalDate;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

public class SlackBotService {
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
                    : tasks.findDigestTasks(TaskStatus.TODO, java.util.Objects.requireNonNull(message.digestDate()).plusDays(3));
            if (message.meetingId() == null) message = digest(message.digestDate(), members);
            if (message.meetingId() != null && results.get(message.meetingId()).status() == com.meetodo.meeting.MeetingStatus.FAILED) members = List.of();
            var rendered = render(message, members);
            var response = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channel)
                    .text(rendered.text()).blocksAsString(rendered.blocksJson()).parse("none").linkNames(false)
                    .unfurlLinks(false).unfurlMedia(false).build());
            if (!response.isOk() || response.getTs() == null || response.getChannel() == null)
                throw new NotificationDeliveryException("SLACK_BOT_POST_FAILED");
            String taskIds = mapper.writeValueAsString(members.stream().map(Task::getId).toList());
            NotificationMessage posted = message;
            transactions.executeWithoutResult(status -> {
                var meeting = posted.meetingId() == null ? null : meetings.findById(posted.meetingId()).orElseThrow();
                if (meeting != null) meeting.recordSlackMessage(response.getChannel(), response.getTs());
                references.save(new SlackMessageReference(response.getChannel(), response.getTs(), meeting, posted.digestDate(), taskIds));
            });
        } catch (Exception exception) { throw new NotificationDeliveryException("SLACK_BOT_POST_FAILED"); }
    }

    public void complete(String channelId, String messageTs, String actor, String value) {
        if (channelId == null || messageTs == null || SlackBotRenderer.mention(actor).isEmpty()) return;
        SlackMessageReference reference;
        try {
            long taskId = Long.parseLong(value);
            reference = references.findByChannelIdAndMessageTs(channelId, messageTs).orElseThrow();
            List<Long> ids = mapper.readValue(reference.getTaskIds(), new TypeReference<>() { });
            if (!ids.contains(taskId)) throw new IllegalArgumentException();
            taskService.completeFromSlack(taskId, actor);
        } catch (TaskCompletionForbiddenException exception) {
            ephemeral(channelId, actor, "담당자만 완료할 수 있어요"); return;
        } catch (Exception exception) {
            ephemeral(channelId, actor, "등록된 메시지와 할 일을 확인할 수 없어요."); return;
        }
        try {
            List<Long> ids = mapper.readValue(reference.getTaskIds(), new TypeReference<>() { });
            List<Task> current = tasks.findAllWithAssigneeByIdIn(ids);
            NotificationMessage message = reference.getMeetingId() == null
                    ? digest(reference.getDigestDate(), current) : messages.from(results.get(reference.getMeetingId()));
            var rendered = render(message, current);
            var response = client.chatUpdate(ChatUpdateRequest.builder().channel(channelId).ts(messageTs)
                    .text(rendered.text()).blocksAsString(rendered.blocksJson()).parse("none").linkNames(false).build());
            if (!response.isOk()) throw new IllegalStateException();
        } catch (Exception exception) {
            ephemeral(channelId, actor, "완료 상태는 저장했지만 메시지를 갱신하지 못했어요. GET /api/tasks에서 확인하세요.");
        }
    }

    private SlackBotRenderer.Rendered render(NotificationMessage message, List<Task> current) {
        return renderer.render(message, current, message.meetingId() == null ? java.util.Map.of()
                : results.get(message.meetingId()).tasks().stream().collect(java.util.stream.Collectors.toMap(t -> t.taskId(), t -> t)));
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
