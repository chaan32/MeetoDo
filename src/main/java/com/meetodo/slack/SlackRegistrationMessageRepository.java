package com.meetodo.slack;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlackRegistrationMessageRepository extends JpaRepository<SlackRegistrationMessage, Long> {
    List<SlackRegistrationMessage> findAllByRequestId(Long requestId);
}
