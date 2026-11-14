package com.meetodo.meeting;

import com.meetodo.usage.UsageReport;

public record AgentResult(String summary, int llmCallCount, int toolCallCount, int validationFailureCount,
                          UsageReport usageReport) {
    public AgentResult(String summary, int llmCalls, int toolCalls, int validationFailures) {
        this(summary, llmCalls, toolCalls, validationFailures, UsageReport.empty());
    }
}
