package com.meetodo.slack;

import com.meetodo.task.TaskAssignedEvent;
import com.meetodo.task.TaskChangedEvent;
import com.meetodo.task.TaskCreatedEvent;
import com.meetodo.member.MemberChangedEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.slf4j.LoggerFactory;

@Component
public class SlackHomeRefreshListener {
    private final ObjectProvider<SlackHomeService> homes;
    public SlackHomeRefreshListener(ObjectProvider<SlackHomeService> homes) { this.homes = homes; }
    @TransactionalEventListener public void created(TaskCreatedEvent event) { refresh(event.taskId(), false); }
    @TransactionalEventListener public void changed(TaskChangedEvent event) { refresh(event.taskId(), false); }
    @TransactionalEventListener public void assigned(TaskAssignedEvent event) { refresh(event.taskId(), true); }
    @TransactionalEventListener public void memberChanged(MemberChangedEvent event) {
        try { var home = homes.getIfAvailable(); if (home != null) home.refreshAll(); }
        catch (Exception ignored) { LoggerFactory.getLogger(getClass()).warn("Slack home refresh failed: INTERNAL_ERROR"); }
    }
    private void refresh(Long id, boolean assignment) {
        try { var home = homes.getIfAvailable(); if (home != null) home.refreshTask(id, assignment); }
        catch (Exception ignored) { LoggerFactory.getLogger(getClass()).warn("Slack home refresh failed: INTERNAL_ERROR"); }
    }
}
