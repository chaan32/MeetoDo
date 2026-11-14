package com.meetodo.meeting;

public class MeetingAgentException extends RuntimeException {

    private final String errorCode;
    private final int llmCallCount;
    private final int toolCallCount;
    private final com.meetodo.usage.UsageReport usageReport;

    public MeetingAgentException(String errorCode, int llmCallCount, int toolCallCount) {
        this(errorCode, llmCallCount, toolCallCount, com.meetodo.usage.UsageReport.empty());
    }

    public MeetingAgentException(String errorCode, int llmCallCount, int toolCallCount,
                                 com.meetodo.usage.UsageReport usageReport) {
        super(publicMessage(errorCode));
        this.usageReport = usageReport;
        this.errorCode = errorCode;
        this.llmCallCount = llmCallCount;
        this.toolCallCount = toolCallCount;
    }

    private static String publicMessage(String errorCode) {
        return switch (errorCode) {
            case "TOOL_CALL_LIMIT_EXCEEDED" -> "회의 처리 중 도구 호출 한도를 초과했습니다.";
            case "EMPTY_LLM_RESPONSE" -> "모델이 최종 응답을 반환하지 않았습니다.";
            default -> "회의 분석 중 오류가 발생했습니다.";
        };
    }

    public com.meetodo.usage.UsageReport getUsageReport() { return usageReport; }
    public String getErrorCode() { return errorCode; }
    public int getLlmCallCount() { return llmCallCount; }
    public int getToolCallCount() { return toolCallCount; }
}
