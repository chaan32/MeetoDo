package com.meetodo.slack;

import com.meetodo.task.TaskReassignedEvent;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SlackTaskReassignmentListener {
    private final ObjectProvider<SlackBotService> bots;
    public SlackTaskReassignmentListener(ObjectProvider<SlackBotService> bots) { this.bots = bots; }

    @TransactionalEventListener
    public void afterCommit(TaskReassignedEvent event) {
        try { var bot = bots.getIfAvailable(); if (bot != null) bot.reassigned(event); }
        catch (Exception ignored) { LoggerFactory.getLogger(getClass()).warn("Slack reassignment notification failed: INTERNAL_ERROR"); }
    }
}
