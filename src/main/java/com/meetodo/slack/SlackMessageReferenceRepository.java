package com.meetodo.slack;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackMessageReferenceRepository extends JpaRepository<SlackMessageReference, Long> {
    Optional<SlackMessageReference> findByChannelIdAndMessageTs(String channelId, String messageTs);
}
