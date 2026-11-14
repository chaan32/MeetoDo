package com.meetodo.audio;

import java.nio.file.Path;

public interface SpeechTranscriber {
    String transcribe(Path audio, String hint);
    String model();

    default TranscriptionResult transcribeWithUsage(Path audio, String hint) {
        return new TranscriptionResult(transcribe(audio, hint), com.meetodo.usage.UsageCall.raw(
                1, model(), null, null, null, null, "unavailable"));
    }
}
