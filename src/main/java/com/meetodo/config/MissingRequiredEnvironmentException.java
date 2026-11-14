package com.meetodo.config;

import java.util.List;

public class MissingRequiredEnvironmentException extends IllegalStateException {
    private final List<String> missingVariables;

    public MissingRequiredEnvironmentException(List<String> missingVariables) {
        super("필수 환경변수가 없습니다: " + String.join(", ", missingVariables));
        this.missingVariables = List.copyOf(missingVariables);
    }

    public List<String> getMissingVariables() { return missingVariables; }
}
