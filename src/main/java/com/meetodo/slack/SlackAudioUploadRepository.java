package com.meetodo.slack;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackAudioUploadRepository extends JpaRepository<SlackAudioUpload, Long> {
    Optional<SlackAudioUpload> findByFileId(String fileId);
}
