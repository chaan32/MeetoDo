package com.meetodo.meeting;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {
    @Query("select log from ToolCallLog log where log.meeting.id = :meetingId order by log.callOrder asc")
    List<ToolCallLog> findAllByMeetingIdOrderByCallOrderAsc(@Param("meetingId") Long meetingId);

    Optional<ToolCallLog> findTopByMeeting_IdOrderByCallOrderDesc(Long meetingId);
}
