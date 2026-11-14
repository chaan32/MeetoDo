package com.meetodo.usage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UsageReport(List<UsageCall> chatCalls, UsageCall transcription) {
    public UsageReport { chatCalls = chatCalls == null ? List.of() : List.copyOf(chatCalls); }
    public static UsageReport empty() { return new UsageReport(List.of(), null); }
    private List<UsageCall> calls() {
        return Stream.concat(chatCalls.stream(), Stream.ofNullable(transcription)).toList();
    }
    private Long sum(Function<UsageCall, Long> field) {
        List<Long> values = calls().stream().map(field).filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToLong(Long::longValue).sum();
    }
    @JsonProperty public Long inputTokens() { return sum(UsageCall::inputTokens); }
    @JsonProperty public Long outputTokens() { return sum(UsageCall::outputTokens); }
    @JsonProperty public Long totalTokens() { return sum(UsageCall::totalTokens); }
    @JsonProperty public String usageStatus() {
        List<UsageCall> calls = calls();
        if (calls.isEmpty() || calls.stream().noneMatch(UsageCall::hasUsage)) return "UNAVAILABLE";
        return calls.stream().allMatch(UsageCall::available) ? "AVAILABLE" : "PARTIAL";
    }
    @JsonProperty public BigDecimal estimatedCostUsd() {
        List<UsageCall> calls = calls();
        if (calls.isEmpty() || calls.stream().anyMatch(call -> call.estimatedCostUsd() == null)) return null;
        return calls.stream().map(UsageCall::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    @JsonProperty public String costStatus() {
        if ("UNAVAILABLE".equals(usageStatus())) return "USAGE_UNAVAILABLE";
        if ("PARTIAL".equals(usageStatus())) return "PARTIAL_USAGE";
        if (estimatedCostUsd() != null) return "ESTIMATED";
        return calls().stream().anyMatch(call -> call.estimatedCostUsd() != null) ? "PARTIAL_PRICE" : "PRICE_NOT_CONFIGURED";
    }
}
