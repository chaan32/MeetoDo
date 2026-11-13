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
    private com.meetodo.member.MemberRepository members;
    public void setMembers(com.meetodo.member.MemberRepository members) { this.members = members; }
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

    public record InputPublication(String inputChannel, String inputTs, String resultChannel, String resultTs) {
        public boolean routed() { return inputChannel != null && inputChannel.startsWith("D") && !inputChannel.equals(resultChannel); }
        public void attach(com.meetodo.meeting.Meeting meeting) {
            if (routed()) meeting.recordSlackInput(inputChannel, inputTs);
            meeting.recordSlackMessage(resultChannel, resultTs);
        }
    }

    public InputPublication prepareInput(String originChannel, String progressTs) {
        if (originChannel == null || !originChannel.matches("[CDG][A-Z0-9]+") || progressTs == null || progressTs.isBlank())
            throw delivery("CONFIGURATION_ERROR");
        if (!originChannel.startsWith("D")) return new InputPublication(originChannel, progressTs, originChannel, progressTs);
        if (channel == null || !channel.matches("[CG][A-Z0-9]+")) throw delivery("CONFIGURATION_ERROR");
        try {
            var response = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channel)
                    .text("⏳ 회의록 정리 중...").parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).build());
            if (response == null || !response.isOk()) throw delivery(response == null ? null : response.getError());
            if (!channel.equals(response.getChannel()) || response.getTs() == null || response.getTs().isBlank()) throw delivery(null);
            return new InputPublication(originChannel, progressTs, channel, response.getTs());
        } catch (NotificationDeliveryException failure) { throw failure; }
        catch (Exception ignored) { throw delivery(null); }
    }

    public void discardInputPublication(InputPublication publication) {
        if (publication != null && publication.routed()) deleteProgress(publication.resultChannel(), publication.resultTs());
    }

    public void showInputNotice(String channelId, String timestamp, String text) {
        if (channelId == null || !channelId.matches("[CDG][A-Z0-9]+") || timestamp == null || timestamp.isBlank()) return;
        try {
            var response = client.chatUpdate(ChatUpdateRequest.builder().channel(channelId).ts(timestamp).text(text)
                    .blocksAsString("[]").parse("none").linkNames(false).build());
            if (response == null || !response.isOk()) log.warn("Slack input receipt update failed: INTERNAL_ERROR");
        } catch (Exception ignored) { log.warn("Slack input receipt update failed: INTERNAL_ERROR"); }
    }

    public void startInput(com.meetodo.meeting.Meeting meeting, String channelId) {
        try {
            if (channelId == null || !channelId.matches("[CDG][A-Z0-9]+")) throw delivery(null);
            var response = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channelId)
                    .text("⏳ 회의록 정리 중...").parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).build());
            if (!response.isOk()) throw delivery(response.getError());
            if (response.getChannel() == null || response.getTs() == null) throw delivery(null);
            if (channelId.startsWith("D")) meeting.recordSlackInput(response.getChannel(), response.getTs());
            prepareInput(response.getChannel(), response.getTs()).attach(meeting);
        } catch (NotificationDeliveryException exception) { throw exception; }
        catch (Exception exception) { throw delivery(null); }
    }

    public void discardInputProgress(com.meetodo.meeting.Meeting candidate) {
        deleteProgress(candidate.getSlackChannelId(), candidate.getSlackMessageTs());
    }

    private void deleteProgress(String channelId, String timestamp) {
        if (channelId == null || timestamp == null) return;
        try {
            var response = client.chatDelete(ChatDeleteRequest.builder().channel(channelId).ts(timestamp).build());
            if (response == null || !response.isOk()) log.warn("Slack temporary message cleanup failed: INTERNAL_ERROR");
        } catch (Exception ignored) { log.warn("Slack temporary message cleanup failed: INTERNAL_ERROR"); }
    }

    private boolean routedInput(com.meetodo.meeting.Meeting meeting) {
        return meeting != null && meeting.getSlackInputChannelId() != null && meeting.getSlackInputChannelId().startsWith("D")
                && meeting.getSlackInputMessageTs() != null;
    }

    private void receipt(com.meetodo.meeting.Meeting meeting, int count, String destination, String timestamp) {
        String text = "✅ 회의 정리 완료 (할 일 " + count + "건)";
        try {
            var link = client.chatGetPermalink(ChatGetPermalinkRequest.builder().channel(destination).messageTs(timestamp).build());
            if (link == null || !link.isOk() || !SlackBotRenderer.validPermalink(link.getPermalink())) throw delivery(null);
            text += " → <" + link.getPermalink() + "|채널 메시지>";
        } catch (Exception ignored) {
            log.warn("Slack input receipt link failed: INTERNAL_ERROR");
            text += " → 채널 메시지 링크를 가져오지 못했어요.";
        }
        showInputNotice(meeting.getSlackInputChannelId(), meeting.getSlackInputMessageTs(), text);
    }

    private void publicationFailure(com.meetodo.meeting.Meeting meeting, boolean published) {
        if (!routedInput(meeting)) return;
        if (!published) discardInputProgress(meeting);
        showInputNotice(meeting.getSlackInputChannelId(), meeting.getSlackInputMessageTs(),
                "❌ 팀 채널 알림을 완료하지 못했어요 (회의 " + meeting.getId() + ")");
    }

    public void send(NotificationMessage supplied) {
        com.meetodo.meeting.Meeting inputMeeting = null;
        boolean published = false;
        try {
            NotificationMessage message = supplied.meetingId() == null ? supplied : messages.from(results.get(supplied.meetingId()));
            List<Task> members = message.meetingId() != null
                    ? tasks.findAllWithAssigneeByIdIn(results.get(message.meetingId()).tasks().stream().map(t -> t.taskId()).toList())
                    : tasks.findDigestTasks(TaskStatus.TODO, Objects.requireNonNull(message.digestDate()).plusDays(3));
            if (message.meetingId() == null) message = digest(message.digestDate(), members);
            if (message.meetingId() != null && results.get(message.meetingId()).status() == com.meetodo.meeting.MeetingStatus.FAILED) members = List.of();
            var meeting = message.meetingId() == null ? null : meetings.findById(message.meetingId()).orElse(null);
            inputMeeting = meeting;
            if (routedInput(meeting) && results.get(message.meetingId()).status() == com.meetodo.meeting.MeetingStatus.FAILED) {
                discardInputProgress(meeting);
                showInputNotice(meeting.getSlackInputChannelId(), meeting.getSlackInputMessageTs(),
                        "❌ 회의록 정리에 실패했어요 (회의 " + meeting.getId() + ")");
                return;
            }
            String publishedChannel = meeting != null && meeting.getSlackMessageTs() != null ? meeting.getSlackChannelId() : channel;
            String publishedTs = meeting == null ? null : meeting.getSlackMessageTs();
            if (publishedTs != null && results.get(message.meetingId()).status() == com.meetodo.meeting.MeetingStatus.FAILED)
                message = new NotificationMessage(message.meetingId(), "❌ 회의록 정리에 실패했어요 (회의 " + message.meetingId() + ")",
                        List.of(), List.of(), message.footer());
            var rendered = render(message, members);
            if (publishedTs == null) {
                var response = client.chatPostMessage(post(publishedChannel, rendered));
                if (!response.isOk()) throw delivery(response.getError());
                if (response.getTs() == null || response.getChannel() == null) throw delivery(null);
                publishedChannel = response.getChannel(); publishedTs = response.getTs();
            } else {
                var response = client.chatUpdate(ChatUpdateRequest.builder().channel(publishedChannel).ts(publishedTs)
                        .text(rendered.text()).blocksAsString(rendered.blocksJson()).parse("none").linkNames(false).build());
                if (!response.isOk()) throw delivery(response.getError());
            }
            published = true;
            String destination = publishedChannel, timestamp = publishedTs;
            String taskIds = taskIds(members);
            NotificationMessage posted = message;
            transactions.executeWithoutResult(status -> {
                var stored = posted.meetingId() == null ? null : meetings.findById(posted.meetingId()).orElseThrow();
                if (stored != null) stored.recordSlackMessage(destination, timestamp);
                var reference = references.findByChannelIdAndMessageTs(destination, timestamp)
                        .orElseGet(() -> new SlackMessageReference(destination, timestamp, stored, posted.digestDate(), taskIds));
                reference.recordTasks(taskIds);
                references.save(reference);
            });
            if (routedInput(meeting)) receipt(meeting, members.stream().filter(task -> !task.isDeleted()).toList().size(), destination, timestamp);
            if (dmEnabled && message.meetingId() != null) {
                try { sendDirectMessages(message, members, destination, timestamp); }
                catch (Exception ignored) { log.warn("Slack DM processing failed: INTERNAL_ERROR"); }
            }
        } catch (NotificationDeliveryException exception) { publicationFailure(inputMeeting, published); throw exception; }
        catch (Exception exception) { publicationFailure(inputMeeting, published); throw delivery(null); }
    }

    private void sendDirectMessages(NotificationMessage message, List<Task> members, String channelId, String messageTs) {
        sendDirectMessages(message, members, channelId, messageTs, null, false);
    }

    private void sendDirectMessages(NotificationMessage message, List<Task> members, String channelId, String messageTs, String actor, boolean assignment) {
        Map<Long, MeetingTaskResponse> details = metadata(message);
        Map<String,List<Task>> recipients = members.stream()
                .filter(task -> assignment || details.containsKey(task.getId()) && "CREATED".equals(details.get(task.getId()).resultType()))
                .filter(task -> task.getAssignee() != null && !SlackBotRenderer.mention(task.getAssignee().getSlackUserId()).isEmpty())
                .collect(Collectors.groupingBy(task -> task.getAssignee().getSlackUserId(), LinkedHashMap::new, Collectors.toList()));
        if (recipients.isEmpty()) return;
        String permalink = null;
        String linkError = null;
        try {
            if (channelId != null && messageTs != null) {
            var response = client.chatGetPermalink(ChatGetPermalinkRequest.builder().channel(channelId).messageTs(messageTs).build());
            if (!response.isOk()) throw delivery(response.getError());
            if (!SlackBotRenderer.validPermalink(response.getPermalink())) throw delivery(null);
            permalink = response.getPermalink();
            }
        } catch (Exception exception) { linkError = errorCode(exception); }
        for (var recipient : recipients.entrySet()) sendNotificationDm(message, recipient.getValue(), details, permalink,
                actor, SlackMessageReference.NotificationKind.ASSIGNMENT, recipient.getKey(), linkError);
    }

    private void sendNotificationDm(NotificationMessage message, List<Task> current, Map<Long, MeetingTaskResponse> details,
            String permalink, String actor, SlackMessageReference.NotificationKind kind, String recipient, String linkError) {
        if (SlackBotRenderer.mention(recipient).isEmpty()) return;
        String dmChannel = null, dmTs = null, error = linkError;
        if (error == null) {
            try {
                var opened = client.conversationsOpen(ConversationsOpenRequest.builder().users(List.of(recipient)).build());
                if (!opened.isOk()) throw delivery(opened.getError());
                if (opened.getChannel() == null || opened.getChannel().getId() == null) throw delivery(null);
                dmChannel = opened.getChannel().getId();
                var rendered = renderer.renderDirectMessage(message, current, details, permalink, actor, kind, recipient);
                var response = client.chatPostMessage(post(dmChannel, rendered));
                if (!response.isOk()) throw delivery(response.getError());
                if (response.getTs() == null || response.getChannel() == null) throw delivery(null);
                dmChannel = response.getChannel(); dmTs = response.getTs();
            } catch (Exception exception) { error = errorCode(exception); }
        }
        try {
            String storedChannel = dmChannel, storedTs = dmTs, storedError = error;
            String ids = taskIds(current);
            transactions.executeWithoutResult(status -> references.save(SlackMessageReference.dm(storedChannel, storedTs,
                    message.meetingId() == null ? null : meetings.findById(message.meetingId()).orElseThrow(), ids, recipient,
                    storedError == null ? SlackMessageReference.DeliveryStatus.SENT : SlackMessageReference.DeliveryStatus.FAILED,
                    storedError, permalink).notification(kind, actor)));
            if (error != null) log.warn("Slack DM delivery failed: {}", error);
        } catch (Exception ignored) { log.warn("Slack DM delivery record failed: INTERNAL_ERROR"); }
    }

    public void complete(String channelId, String messageTs, String actor, String value) {
        if (channelId == null || messageTs == null || SlackBotRenderer.mention(actor).isEmpty()) return;
        SlackMessageReference reference;
        boolean alreadyDone;
        long taskId;
        try {
            taskId = Long.parseLong(value);
            reference = references.findByChannelIdAndMessageTs(channelId, messageTs).orElseThrow();
            if (reference.getDeliveryStatus() != SlackMessageReference.DeliveryStatus.SENT || !ids(reference).contains(taskId)
                    || reference.getMessageType() == SlackMessageReference.MessageType.DM && !Objects.equals(actor, reference.getTargetSlackUserId())) throw new IllegalArgumentException();
            alreadyDone = tasks.findAllWithAssigneeByIdIn(List.of(taskId)).stream().anyMatch(task -> task.getStatus() == TaskStatus.DONE);
            taskService.completeFromSlack(taskId, actor);
        } catch (TaskCompletionForbiddenException exception) {
            ephemeral(channelId, actor, "담당자만 완료할 수 있어요"); return;
        } catch (Exception exception) {
            ephemeral(channelId, actor, "등록된 메시지와 할 일을 확인할 수 없어요."); return;
        }
        if (alreadyDone) refreshTask(taskId, reference);
    }

    public void refreshTask(Long taskId) { refreshTask(taskId, null); }

    private void refreshTask(Long taskId, SlackMessageReference reference) {
        List<SlackMessageReference> related = new ArrayList<>();
        try {
            // shortcut: scan stored membership for this small app; index a task-message relation when message history grows.
            related.addAll(references.findAll().stream().filter(ref -> ref.getDeliveryStatus() == SlackMessageReference.DeliveryStatus.SENT).toList());
        } catch (Exception ignored) { log.warn("Slack message lookup failed: INTERNAL_ERROR"); }
        if (reference != null && related.stream().noneMatch(ref -> Objects.equals(ref.getChannelId(), reference.getChannelId()) && Objects.equals(ref.getMessageTs(), reference.getMessageTs()))) related.add(reference);
        for (SlackMessageReference ref : related) {
            try {
                List<Long> ids = ids(ref);
                if (!ids.contains(taskId)) continue;
                List<Task> current = tasks.findAllWithAssigneeByIdIn(ids);
                NotificationMessage message = ref.getMeetingId() != null ? messages.from(results.get(ref.getMeetingId()))
                        : ref.getDigestDate() != null ? digest(ref.getDigestDate(), current) : assignmentMessage(null);
                var rendered = ref.getMessageType() == SlackMessageReference.MessageType.DM
                        ? renderer.renderDirectMessage(message, current, metadata(message), ref.getPermalink(),
                                ref.getNotificationActorSlackUserId(), ref.getNotificationKind(), ref.getTargetSlackUserId()) : render(message, current);
                var response = client.chatUpdate(ChatUpdateRequest.builder().channel(ref.getChannelId()).ts(ref.getMessageTs())
                        .text(rendered.text()).blocksAsString(rendered.blocksJson()).parse("none").linkNames(false).build());
                if (!response.isOk()) throw delivery(response.getError());
            } catch (Exception exception) {
                log.warn("Slack message refresh failed: {}", errorCode(exception));
            }
        }
    }

    public void assignCandidate(String channelId, String messageTs, String actor, String value) {
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            assign(channelId, messageTs, actor, Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception exception) { assignmentFailure(channelId, actor, exception); }
    }

    public void assignSelection(String channelId, String messageTs, String actor, String blockId, String value) {
        try { assign(channelId, messageTs, actor, assignmentTaskId(blockId), Long.parseLong(value)); }
        catch (Exception exception) { assignmentFailure(channelId, actor, exception); }
    }

    public void assignUser(String channelId, String messageTs, String actor, String blockId, String user) {
        try {
            long taskId = assignmentTaskId(blockId);
            authorize(channelId, messageTs, actor, taskId);
            if (refreshIfAssignedOrDone(taskId)) return;
            if (SlackBotRenderer.mention(user).isEmpty() || members == null) throw new IllegalArgumentException();
            var member = members.findBySlackUserIdAndActiveTrue(user).orElseThrow(() ->
                    new TaskAssignmentException("MEMBER_NOT_FOUND", "등록된 팀원이 아니에요"));
            taskService.assign(taskId, member.getId(), actor);
        } catch (Exception exception) { assignmentFailure(channelId, actor, exception); }
    }

    private long assignmentTaskId(String blockId) {
        if (blockId == null || !blockId.startsWith(SlackBotRenderer.ASSIGN_BLOCK_PREFIX)) throw new IllegalArgumentException();
        return Long.parseLong(blockId.substring(SlackBotRenderer.ASSIGN_BLOCK_PREFIX.length()));
    }

    private void assign(String channelId, String messageTs, String actor, long taskId, long memberId) throws java.io.IOException {
        authorize(channelId, messageTs, actor, taskId);
        performAssignment(taskId, memberId, actor);
    }

    private void performAssignment(long taskId, long memberId, String actor) {
        if (!refreshIfAssignedOrDone(taskId)) taskService.assign(taskId, memberId, actor);
    }

    private boolean refreshIfAssignedOrDone(long taskId) {
        boolean noChange = tasks.findAllWithAssigneeByIdIn(List.of(taskId)).stream()
                .anyMatch(task -> task.getAssignee() != null || task.getStatus() == TaskStatus.DONE);
        if (noChange) refreshTask(taskId);
        return noChange;
    }

    void authorizeTaskMessage(String channelId, String messageTs, String actor, long taskId) throws java.io.IOException {
        authorize(channelId, messageTs, actor, taskId);
    }

    private void authorize(String channelId, String messageTs, String actor, long taskId) throws java.io.IOException {
        if (channelId == null || messageTs == null || SlackBotRenderer.mention(actor).isEmpty()) throw new IllegalArgumentException();
        var reference = references.findByChannelIdAndMessageTs(channelId, messageTs).orElseThrow();
        if (reference.getDeliveryStatus() != SlackMessageReference.DeliveryStatus.SENT || !ids(reference).contains(taskId)
                    || reference.getMessageType() == SlackMessageReference.MessageType.DM && !Objects.equals(actor, reference.getTargetSlackUserId())) throw new IllegalArgumentException();
    }

    private void assignmentFailure(String channelId, String actor, Exception exception) {
        String message = "등록된 메시지와 담당자 정보를 확인할 수 없어요.";
        if (exception instanceof TaskAssignmentException failure) message = failure.getMessage();
        else if (exception instanceof com.meetodo.task.exception.DuplicateTaskException duplicate)
            message = "이미 같은 할 일이 있어요 (#" + duplicate.getExistingTaskId() + ")";
        else if (exception instanceof com.meetodo.task.exception.ResourceNotFoundException missing && "MEMBER_NOT_FOUND".equals(missing.getCode()))
            message = "등록된 팀원이 아니에요";
        if (channelId != null && !SlackBotRenderer.mention(actor).isEmpty()) ephemeral(channelId, actor, message);
    }

    public void assigned(Long taskId, String actor) {
        try { refreshTask(taskId); }
        catch (Exception ignored) { log.warn("Slack assignment refresh failed: INTERNAL_ERROR"); }
        if (!dmEnabled) return;
        try {
            List<Task> current = tasks.findAllWithAssigneeByIdIn(List.of(taskId));
            if (current.isEmpty()) return;
            // shortcut: scan message membership until a task-message relation is needed for larger history.
            var source = references.findAll().stream().filter(ref -> ref.getMessageType() == SlackMessageReference.MessageType.CHANNEL
                    && ref.getDeliveryStatus() == SlackMessageReference.DeliveryStatus.SENT).filter(ref -> {
                        try { return ids(ref).contains(taskId); } catch (Exception ignored) { return false; }
                    }).findFirst().orElse(null);
            Long meetingId = source == null ? null : source.getMeetingId();
            sendDirectMessages(assignmentMessage(meetingId), current, source == null ? null : source.getChannelId(),
                    source == null ? null : source.getMessageTs(), actor, true);
        } catch (Exception ignored) { log.warn("Slack assignment DM processing failed: INTERNAL_ERROR"); }
    }

    public void reassigned(TaskReassignedEvent event) {
        try { refreshTask(event.taskId()); }
        catch (Exception ignored) { log.warn("Slack reassignment refresh failed: INTERNAL_ERROR"); }
        if (!dmEnabled) return;
        try {
            List<Task> current = tasks.findAllWithAssigneeByIdIn(List.of(event.taskId()));
            if (current.isEmpty()) return;
            Task task = current.getFirst();
            var source = references.findAll().stream().filter(ref -> ref.getMessageType() == SlackMessageReference.MessageType.CHANNEL
                    && ref.getDeliveryStatus() == SlackMessageReference.DeliveryStatus.SENT).filter(ref -> {
                        try { return ids(ref).contains(event.taskId()); } catch (Exception ignored) { return false; }
                    }).findFirst().orElse(null);
            var message = assignmentMessage(source == null ? null : source.getMeetingId());
            String permalink = null, linkError = null;
            if (source != null) {
                try {
                    var response = client.chatGetPermalink(ChatGetPermalinkRequest.builder().channel(source.getChannelId()).messageTs(source.getMessageTs()).build());
                    if (!response.isOk()) throw delivery(response.getError());
                    if (!SlackBotRenderer.validPermalink(response.getPermalink())) throw delivery(null);
                    permalink = response.getPermalink();
                } catch (Exception failure) { linkError = errorCode(failure); }
            }
            if (task.getAssignee() != null) sendNotificationDm(message, current, metadata(message), permalink, event.actorSlackUserId(),
                    SlackMessageReference.NotificationKind.REASSIGNMENT, task.getAssignee().getSlackUserId(), linkError);
            sendNotificationDm(message, current, metadata(message), permalink, event.actorSlackUserId(),
                    SlackMessageReference.NotificationKind.HANDOFF, event.previousSlackUserId(), linkError);
        } catch (Exception ignored) { log.warn("Slack reassignment DM processing failed: INTERNAL_ERROR"); }
    }

    private NotificationMessage assignmentMessage(Long meetingId) {
        return new NotificationMessage(meetingId, "📝 새 할 일이 배정됐어요", List.of(), List.of(),
                meetingId == null ? "MeetoDo" : "회의 " + meetingId);
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

    void ephemeral(String channelId, String user, String text) {
        if (channelId == null || !channelId.matches("[CDG][A-Z0-9]{1,79}") || SlackBotRenderer.mention(user).isEmpty()) return;
        try { client.chatPostEphemeral(ChatPostEphemeralRequest.builder().channel(channelId).user(user).text(text).build()); }
        catch (Exception ignored) { }
    }
}
