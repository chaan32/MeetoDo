package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Profile("local & !test")
public class NotificationConfiguration {
    @Bean
    @Conditional(WebhookConfigured.class)
    Notifier notifier(Environment environment, ObjectMapper mapper) {
        WebhookHttpClient http = new WebhookHttpClient(mapper);
        return "slack".equals(type(environment))
                ? new SlackNotifier(environment.getProperty("SLACK_WEBHOOK_URL"), http, new SlackPayloadRenderer())
                : new DiscordNotifier(environment.getProperty("DISCORD_WEBHOOK_URL"), http, new DiscordPayloadRenderer());
    }

    private static String type(Environment environment) {
        return environment.getProperty("NOTIFIER_TYPE", "").trim().toLowerCase(Locale.ROOT);
    }

    static class WebhookConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String type = type(env);
            return ("slack".equals(type) && StringUtils.hasText(env.getProperty("SLACK_WEBHOOK_URL")))
                    || ("discord".equals(type) && StringUtils.hasText(env.getProperty("DISCORD_WEBHOOK_URL")));
        }
    }
}
