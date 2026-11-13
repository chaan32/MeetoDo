package com.meetodo.task;

public record TaskReassignedEvent(Long taskId, Long previousMemberId, String previousSlackUserId,
                                  Long newMemberId, String actorSlackUserId) {}
