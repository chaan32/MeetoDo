package com.meetodo.task;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskMentionRepository extends JpaRepository<TaskMention, Long> {
    boolean existsByTask_IdAndMeeting_Id(Long taskId, Long meetingId);

    @Query("select distinct mention.meeting.id from TaskMention mention where mention.task.id = :taskId")
    List<Long> findMeetingIdsByTaskId(@Param("taskId") Long taskId);

    @Query("select mention from TaskMention mention join fetch mention.task task "
            + "left join fetch task.assignee where mention.meeting.id = :meetingId")
    List<TaskMention> findAllWithTaskByMeetingId(@Param("meetingId") Long meetingId);

    @Query("select mention.task.id as taskId, count(mention.id) as mentionCount "
            + "from TaskMention mention where mention.task.id in :taskIds "
            + "and mention.meeting.duplicateOfMeeting is null group by mention.task.id")
    List<TaskMentionCount> countByTaskIdsExcludingDuplicateMeetings(@Param("taskIds") Collection<Long> taskIds);

    interface TaskMentionCount {
        Long getTaskId();
        Long getMentionCount();
    }
}
