package com.meetodo.task;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    List<TaskEvent> findAllByTask_IdOrderByAtAscIdAsc(Long taskId);
}
