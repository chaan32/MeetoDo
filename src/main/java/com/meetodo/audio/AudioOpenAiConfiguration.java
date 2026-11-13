package com.meetodo.audio;

import java.time.Duration;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile("(local | docker) & openai & !test")
@Conditional(AudioOpenAiConfiguration.KeyConfigured.class)
public class AudioOpenAiConfiguration {
    @Bean OpenAiAudioApi openAiAudioApi(Environment env) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return OpenAiAudioApi.builder().apiKey(env.getProperty("OPENAI_API_KEY"))
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }

    @Bean SpeechTranscriber speechTranscriber(OpenAiAudioApi api, Environment env) {
        return new OpenAiSpeechTranscriber(api, env.getProperty("MEETODO_STT_MODEL", "gpt-transcribe"));
    }

    static class KeyConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !context.getEnvironment().getProperty("OPENAI_API_KEY", "").isBlank();
        }
    }
}
