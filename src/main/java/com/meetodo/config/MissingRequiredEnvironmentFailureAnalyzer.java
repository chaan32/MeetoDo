package com.meetodo.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

public class MissingRequiredEnvironmentFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingRequiredEnvironmentException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingRequiredEnvironmentException cause) {
        return new FailureAnalysis("필수 환경변수가 없습니다: " + String.join(", ", cause.getMissingVariables()),
                ".env.example을 참고해 값을 설정하세요.", cause);
    }
}
