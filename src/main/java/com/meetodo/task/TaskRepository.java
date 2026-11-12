package com.meetodo.task;

import java.util.List;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

import com.meetodo.member.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {
    // Lock only the task: PostgreSQL cannot lock the nullable side of an assignee outer join.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from Task task where task.id = :id")
    Optional<Task> findByIdForUpdate(@Param("id") Long id);

    @Query("select task from Task task left join fetch task.assignee where task.status = :status and task.dueDate <= :lastDate")
    List<Task> findDigestTasks(@Param("status") TaskStatus status, @Param("lastDate") LocalDate lastDate);
    long countByStatusAndDueDateIsNull(TaskStatus status);

    List<Task> findAllByOrderByCreatedAtDesc();
    List<Task> findAllByAssigneeOrderByCreatedAtDesc(Member assignee);
    List<Task> findAllByAssigneeIsNullOrderByCreatedAtDesc();
    @Query("select task from Task task left join fetch task.assignee where task.assignee = :assignee and task.status = :status order by task.createdAt desc")
    List<Task> findAllByAssigneeAndStatusOrderByCreatedAtDesc(@Param("assignee") Member assignee,
                                                               @Param("status") TaskStatus status);
    @Query("select task from Task task left join fetch task.assignee where task.assignee is null and task.status = :status order by task.createdAt desc")
    List<Task> findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(@Param("status") TaskStatus status);
    List<Task> findAllByMeeting_IdAndTitleIgnoreCase(Long meetingId, String title);
    @Query("select task from Task task left join fetch task.assignee where task.meeting.id = :meetingId order by task.createdAt desc")
    List<Task> findAllByMeeting_IdOrderByCreatedAtDesc(@Param("meetingId") Long meetingId);

    @Query("select task from Task task left join fetch task.assignee where task.id in :ids")
    List<Task> findAllWithAssigneeByIdIn(@Param("ids") Collection<Long> ids);
}
