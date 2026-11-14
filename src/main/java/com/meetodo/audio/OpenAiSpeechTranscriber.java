package com.meetodo.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.meetodo.usage.UsageCall;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.openai.api.OpenAiAudioApi.TranscriptionRequest;
import org.springframework.ai.openai.api.OpenAiAudioApi.TranscriptResponseFormat;

public class OpenAiSpeechTranscriber implements SpeechTranscriber {
    private final OpenAiAudioApi api;
    private final String model;

    public OpenAiSpeechTranscriber(OpenAiAudioApi api, String model) {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("Invalid audio transcription model");
        }
        this.api = api;
        this.model = model;
    }

    @Override public String transcribe(Path audio, String hint) {
        return transcribeWithUsage(audio, hint).text();
    }

    @Override public TranscriptionResult transcribeWithUsage(Path audio, String hint) {
        try {
            String name = audio.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            var request = TranscriptionRequest.builder().file(Files.readAllBytes(audio))
                    .fileName("meeting." + extension).model(model).prompt(hint)
                    .responseFormat(TranscriptResponseFormat.JSON).build();
            var response = api.createTranscription(request, TranscriptionResponse.class).getBody();
            return new TranscriptionResult(response.text(), usage(response.usage()));
        } catch (Exception exception) {
            // Provider errors may contain credentials, file paths or transcript content.
            throw new IllegalStateException("Audio transcription failed");
        }
    }

    private UsageCall usage(TranscriptionUsage usage) {
        if (usage != null && "tokens".equals(usage.type()) && nonNegative(usage.inputTokens())
                && nonNegative(usage.outputTokens()) && nonNegative(usage.totalTokens())
                && (usage.inputTokens() != null || usage.outputTokens() != null || usage.totalTokens() != null))
            return UsageCall.raw(1, model, usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), null, "tokens");
        if (usage != null && "duration".equals(usage.type()) && usage.seconds() != null && usage.seconds().signum() >= 0)
            return UsageCall.raw(1, model, null, null, null, usage.seconds(), "duration");
        return UsageCall.raw(1, model, null, null, null, null, "unavailable");
    }

    private static boolean nonNegative(Long value) { return value == null || value >= 0; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TranscriptionResponse(String text, TranscriptionUsage usage) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TranscriptionUsage(String type,
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens,
            @JsonProperty("total_tokens") Long totalTokens, BigDecimal seconds) { }

    @Override public String model() { return model; }
}
