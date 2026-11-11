package com.meetodo.meeting;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "tool_call_logs", indexes = @Index(name = "idx_tool_call_logs_meeting_order", columnList = "meeting_id, call_order"))
public class ToolCallLog {

    public static final int MAX_ARGUMENTS_LENGTH = 4000;
    public static final int MAX_RESULT_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(length = MAX_ARGUMENTS_LENGTH)
    private String arguments;

    @Column(length = MAX_RESULT_LENGTH)
    private String result;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "call_order", nullable = false)
    private int callOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ToolCallLog() {
    }

    private ToolCallLog(Meeting meeting, String toolName, String arguments, String result,
                        boolean success, String errorCode, String errorMessage,
                        long latencyMs, int callOrder) {
        if (meeting == null || toolName == null || toolName.isBlank())
            throw new IllegalArgumentException("meeting and toolName are required");
        if (latencyMs < 0 || callOrder < 1)
            throw new IllegalArgumentException("latency must be non-negative and callOrder positive");
        this.meeting = meeting;
        this.toolName = toolName;
        this.arguments = truncate(arguments, MAX_ARGUMENTS_LENGTH);
        this.result = truncate(result, MAX_RESULT_LENGTH);
        this.success = success;
        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage, 500);
        this.latencyMs = latencyMs;
        this.callOrder = callOrder;
    }

    public static ToolCallLog success(Meeting meeting, String toolName, String arguments,
                                      String result, long latencyMs, int callOrder) {
        return new ToolCallLog(meeting, toolName, arguments, result, true, null, null, latencyMs, callOrder);
    }

    public static ToolCallLog failure(Meeting meeting, String toolName, String arguments,
                                      String errorCode, String errorMessage, long latencyMs, int callOrder) {
        return new ToolCallLog(meeting, toolName, arguments, null, false,
                errorCode, errorMessage, latencyMs, callOrder);
    }

    public static ToolCallLog failure(Meeting meeting, String toolName, String arguments, String result,
                                      String errorCode, String errorMessage, long latencyMs, int callOrder) {
        return new ToolCallLog(meeting, toolName, arguments, result, false,
                errorCode, errorMessage, latencyMs, callOrder);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Meeting getMeeting() { return meeting; }
    public Long getMeetingId() { return meeting == null ? null : meeting.getId(); }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getResult() { return result; }
    public boolean getSuccess() { return success; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public long getLatencyMs() { return latencyMs; }
    public int getCallOrder() { return callOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
