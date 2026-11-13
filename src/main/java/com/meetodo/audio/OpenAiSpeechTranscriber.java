package com.meetodo.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.openai.api.OpenAiAudioApi.StructuredResponse;
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
        try {
            String name = audio.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            var request = TranscriptionRequest.builder().file(Files.readAllBytes(audio))
                    .fileName("meeting." + extension).model(model).prompt(hint)
                    .responseFormat(TranscriptResponseFormat.JSON).build();
            return api.createTranscription(request, StructuredResponse.class).getBody().text();
        } catch (Exception exception) {
            // Provider errors may contain credentials, file paths or transcript content.
            throw new IllegalStateException("Audio transcription failed");
        }
    }

    @Override public String model() { return model; }
}
