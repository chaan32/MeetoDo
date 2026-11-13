package com.meetodo.slack;

import com.meetodo.task.TaskChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlackTaskChangedListener {
    private static final Logger log = LoggerFactory.getLogger(SlackTaskChangedListener.class);
    private final ObjectProvider<SlackBotService> bots;
    public SlackTaskChangedListener(ObjectProvider<SlackBotService> bots) { this.bots = bots; }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TaskChangedEvent event) {
        try {
            SlackBotService bot = bots.getIfAvailable();
            if (bot != null) bot.refreshTask(event.taskId());
        } catch (Exception ignored) { log.warn("Slack task refresh failed: INTERNAL_ERROR"); }
    }
}
