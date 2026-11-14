package com.meetodo.rag.index;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagIndexRunRepository extends JpaRepository<RagIndexRun, Long> {
    Optional<RagIndexRun> findFirstByMeetingIdOrderByIdDesc(Long meetingId);
    List<RagIndexRun> findAllByOrderByIdDesc();
    List<RagIndexRun> findAllByMeetingId(Long meetingId);
}
