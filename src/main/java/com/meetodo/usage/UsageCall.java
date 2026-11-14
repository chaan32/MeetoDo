package com.meetodo.usage;

import java.math.BigDecimal;

public record UsageCall(int callOrder, String model, Long inputTokens, Long outputTokens, Long totalTokens,
                        BigDecimal durationSeconds, String usageType, BigDecimal estimatedCostUsd, String costStatus) {
    public UsageCall {
        model = model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") ? model : null;
        inputTokens = nonNegative(inputTokens); outputTokens = nonNegative(outputTokens); totalTokens = nonNegative(totalTokens);
        if (durationSeconds != null && durationSeconds.signum() < 0) durationSeconds = null;
    }
    public static UsageCall raw(int order, String model, Long input, Long output, Long total, BigDecimal seconds, String type) {
        return new UsageCall(order, model, input, output, total, seconds, type, null,
                "tokens".equals(type) || "duration".equals(type) || "embedding".equals(type) ? "PRICE_NOT_CONFIGURED" : "USAGE_UNAVAILABLE");
    }
    public boolean available() {
        return "tokens".equals(usageType) && inputTokens != null && outputTokens != null && totalTokens != null
                || "duration".equals(usageType) && durationSeconds != null
                || "embedding".equals(usageType) && inputTokens != null && totalTokens != null;
    }
    public boolean hasUsage() {
        return inputTokens != null || outputTokens != null || totalTokens != null || durationSeconds != null;
    }
    public UsageCall priced(BigDecimal cost, String status) {
        return new UsageCall(callOrder, model, inputTokens, outputTokens, totalTokens, durationSeconds, usageType, cost, status);
    }
    private static Long nonNegative(Long value) { return value == null || value < 0 ? null : value; }
}
