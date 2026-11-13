package com.meetodo.slack;

import com.meetodo.task.TaskAssignedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlackTaskAssignmentListener {
    private static final Logger log = LoggerFactory.getLogger(SlackTaskAssignmentListener.class);
    private final ObjectProvider<SlackBotService> bots;
    public SlackTaskAssignmentListener(ObjectProvider<SlackBotService> bots) { this.bots = bots; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TaskAssignedEvent event) {
        try {
            SlackBotService bot = bots.getIfAvailable();
            if (bot != null) bot.assigned(event.taskId(), event.actorSlackUserId());
        } catch (Exception ignored) { log.warn("Slack assignment notification failed: INTERNAL_ERROR"); }
    }
}
