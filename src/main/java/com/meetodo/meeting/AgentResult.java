package com.meetodo.meeting;

public record AgentResult(String summary, int llmCallCount, int toolCallCount, int validationFailureCount) {
}
