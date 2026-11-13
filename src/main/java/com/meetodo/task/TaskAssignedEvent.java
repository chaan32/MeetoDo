package com.meetodo.task;

public record TaskAssignedEvent(Long taskId, String actorSlackUserId) {}
