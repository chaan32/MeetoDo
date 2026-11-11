package com.meetodo.meeting;

public class MeetingAgentException extends RuntimeException {

    private final String errorCode;
    private final int llmCallCount;
    private final int toolCallCount;

    public MeetingAgentException(String errorCode, int llmCallCount, int toolCallCount) {
        super(publicMessage(errorCode));
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

    public String getErrorCode() { return errorCode; }
    public int getLlmCallCount() { return llmCallCount; }
    public int getToolCallCount() { return toolCallCount; }
}
