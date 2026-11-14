package com.meetodo.rag.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class RagEnabled implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return "true".equalsIgnoreCase(environment.getProperty("MEETODO_RAG_ENABLED", "false").strip())
                && environment.acceptsProfiles(Profiles.of("test | ((local | docker) & openai)"));
    }
}
