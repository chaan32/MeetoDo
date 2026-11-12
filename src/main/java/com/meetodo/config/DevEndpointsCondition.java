package com.meetodo.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Local keeps its existing behavior; Docker demos require explicit opt-in. */
public class DevEndpointsCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        return env.acceptsProfiles(Profiles.of("local")) || (env.acceptsProfiles(Profiles.of("docker"))
                && env.getProperty("meetodo.dev.endpoints-enabled", Boolean.class, false));
    }
}
