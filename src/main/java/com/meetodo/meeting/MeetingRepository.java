package com.meetodo.meeting;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
    Optional<Meeting> findByContentHash(String contentHash);
    List<Meeting> findTop5ByOrderByCreatedAtDesc();
}
