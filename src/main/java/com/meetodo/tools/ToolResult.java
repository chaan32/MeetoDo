package com.meetodo.tools;

/** A compact, model-readable outcome for a tool invocation. */
public record ToolResult(boolean success, String errorCode, String message, String data) {

    public static ToolResult success(String message, String data) {
        return new ToolResult(true, null, message, data);
    }

    public static ToolResult error(String errorCode, String message) {
        return new ToolResult(false, errorCode, message, null);
    }
}
