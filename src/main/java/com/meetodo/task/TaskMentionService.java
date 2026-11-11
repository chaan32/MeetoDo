package com.meetodo.task;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.meetodo.meeting.MeetingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskMentionService {

    private final TaskMentionRepository mentions;
    private final TaskRepository tasks;
    private final MeetingRepository meetings;
    private final Clock clock;
    private final TransactionTemplate insertTransaction;

    public TaskMentionService(TaskMentionRepository mentions, TaskRepository tasks, MeetingRepository meetings,
                              Clock clock, PlatformTransactionManager transactionManager) {
        this.mentions = mentions;
        this.tasks = tasks;
        this.meetings = meetings;
        this.clock = clock;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Long taskId, Long meetingId) {
        if (taskId == null || meetingId == null) return;
        try {
            insertTransaction.executeWithoutResult(status -> {
                if (mentions.existsByTask_IdAndMeeting_Id(taskId, meetingId)) return;
                Task task = tasks.getReferenceById(taskId);
                var meeting = meetings.getReferenceById(meetingId);
                mentions.saveAndFlush(TaskMention.create(task, meeting, Instant.now(clock)));
            });
        } catch (DataIntegrityViolationException conflict) {
            if (!mentions.existsByTask_IdAndMeeting_Id(taskId, meetingId)) throw conflict;
        }
    }

    public Map<Long, Long> counts(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        mentions.countByTaskIdsExcludingDuplicateMeetings(taskIds)
                .forEach(row -> counts.put(row.getTaskId(), row.getMentionCount()));
        return counts;
    }

    public java.util.List<TaskMention> findAllForMeeting(Long meetingId) {
        return mentions.findAllWithTaskByMeetingId(meetingId);
    }
}
