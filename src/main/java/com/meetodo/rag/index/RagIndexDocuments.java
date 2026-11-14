package com.meetodo.rag.index;

import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingStatus;
import com.meetodo.rag.config.RagEnabled;
import com.meetodo.rag.config.RagProperties;
import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskMentionRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Conditional(RagEnabled.class)
@Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
public class RagIndexDocuments {
    private final MeetingRepository meetings;
    private final TaskRepository tasks;
    private final TaskMentionRepository mentions;
    private final TokenTextSplitter splitter;
    private final String namespace;

    public RagIndexDocuments(MeetingRepository meetings, TaskRepository tasks, TaskMentionRepository mentions, RagProperties properties) {
        this.meetings = meetings; this.tasks = tasks; this.mentions = mentions;
        splitter = TokenTextSplitter.builder().withChunkSize(properties.chunkTokens())
                .withMinChunkSizeChars(1).withMinChunkLengthToEmbed(1).withMaxNumChunks(1000).withKeepSeparator(true).build();
        namespace = properties.embeddingModel() + ":" + properties.dimensions() + ":" + properties.chunkTokens() + ":v1";
    }

    public List<Document> forMeeting(Long id) {
        Meeting meeting = meetings.findById(id).filter(value -> value.getStatus() == MeetingStatus.DONE).orElse(null);
        if (meeting == null) return List.of();
        List<Document> result = new ArrayList<>();
        List<Document> chunks = splitter.apply(List.of(new Document(meeting.getRawText())));
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i).getText();
            if (text != null && !text.isBlank()) result.add(document(meeting, "MEETING", null, i, text));
        }
        Map<Long, Task> linked = new TreeMap<>();
        tasks.findAllByMeeting_IdOrderByCreatedAtDesc(id).forEach(task -> linked.put(task.getId(), task));
        mentions.findAllWithTaskByMeetingId(id).forEach(mention -> linked.put(mention.getTask().getId(), mention.getTask()));
        linked.values().stream().filter(task -> !task.isDeleted()).forEach(task -> {
            String owner = task.getAssignee() == null ? "담당자 미정"
                    : task.getAssignee().getFullName() + "(" + task.getAssignee().getTeam() + ")";
            String text = "현재 할 일: " + task.getTitle() + " / 담당자: " + owner + " / 마감일: "
                    + Objects.toString(task.getDueDate(), "없음") + " / 상태: " + task.getStatus();
            result.add(document(meeting, "TASK", task.getId(), 0, text));
        });
        return List.copyOf(result);
    }

    private Document document(Meeting meeting, String kind, Long taskId, int chunk, String text) {
        String key = namespace + ":" + meeting.getId() + ":" + kind + ":" + Objects.toString(taskId, chunk + "");
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kind", kind); metadata.put("meetingId", meeting.getId()); metadata.put("chunkIndex", chunk);
        if (taskId != null) metadata.put("taskId", taskId);
        metadata.put("registeredAt", meeting.getCreatedAt().toString());
        metadata.put("channelId", Objects.toString(meeting.getSlackChannelId(), "")); metadata.put("namespace", namespace);
        return new Document(id, text, metadata);
    }

    public Optional<RagSourceSnapshot> sourceSnapshot(String id, Long meetingId) {
        return forMeeting(meetingId).stream().filter(document -> document.getId().equals(id)).findFirst()
                .map(document -> {
                    Map<String, Object> data = document.getMetadata();
                    return new RagSourceSnapshot(document.getId(), (String) data.get("kind"), meetingId,
                            (Long) data.get("taskId"), (Integer) data.get("chunkIndex"), document.getText(),
                            java.time.Instant.parse((String) data.get("registeredAt")), (String) data.get("channelId"), namespace);
                });
    }

    public List<Long> eligibleMeetingIds() {
        return meetings.findAll().stream().filter(meeting -> meeting.getStatus() == MeetingStatus.DONE)
                .map(Meeting::getId).sorted().toList();
    }
    public String namespace() { return namespace; }

    public List<Long> meetingIdsForTask(Long taskId) {
        Task task = tasks.findById(taskId).orElse(null);
        if (task == null) return List.of();
        Set<Long> ids = new TreeSet<>(mentions.findMeetingIdsByTaskId(taskId));
        if (task.getMeetingId() != null) ids.add(task.getMeetingId());
        return ids.stream().filter(id -> meetings.findById(id).map(meeting -> meeting.getStatus() == MeetingStatus.DONE).orElse(false)).toList();
    }
}
