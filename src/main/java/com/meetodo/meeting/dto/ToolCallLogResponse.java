package com.meetodo.meeting.dto;

import java.time.Instant;

import com.meetodo.meeting.ToolCallLog;

public record ToolCallLogResponse(int callOrder, String toolName, String arguments, String result,
                                  boolean success, String errorCode, String errorMessage,
                                  long latencyMs, Instant createdAt) {
    public static ToolCallLogResponse from(ToolCallLog log) {
        return new ToolCallLogResponse(log.getCallOrder(), log.getToolName(), log.getArguments(),
                log.getResult(), log.getSuccess(), log.getErrorCode(), log.getErrorMessage(),
                log.getLatencyMs(), log.getCreatedAt());
    }
}
