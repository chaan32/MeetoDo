package com.meetodo.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public class SelfHostingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.acceptsProfiles(Profiles.of("test"))) return;

        List<String> required = new ArrayList<>();
        if ("slack-bot".equalsIgnoreCase(environment.getProperty("NOTIFIER_TYPE", "").trim())) {
            required.addAll(List.of("SLACK_BOT_TOKEN", "SLACK_APP_TOKEN", "SLACK_CHANNEL_ID"));
        }
        if (environment.acceptsProfiles(Profiles.of("openai"))) required.add("OPENAI_API_KEY");

        List<String> missing = required.stream()
                .filter(name -> environment.getProperty(name, "").isBlank())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variables: " + String.join(", ", missing));
        }
    }

    @Override
    public int getOrder() {
        // Validate loaded profiles and properties before any SDK bean can initialize.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
