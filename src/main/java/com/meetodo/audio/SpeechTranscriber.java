package com.meetodo.audio;

import java.nio.file.Path;

public interface SpeechTranscriber {
    String transcribe(Path audio, String hint);
    String model();
}
