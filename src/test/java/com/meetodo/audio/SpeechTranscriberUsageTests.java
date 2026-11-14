package com.meetodo.audio;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SpeechTranscriberUsageTests {
    @Test void legacyTranscriberRunsOnceAndExplicitlyReportsUnavailableUsage() {
        // 사용량을 제공하지 않는 기존 어댑터는 전사를 한 번만 수행하고 미제공을 명시한다.
        AtomicInteger calls = new AtomicInteger();
        SpeechTranscriber legacy = new SpeechTranscriber() {
            public String transcribe(Path audio, String hint) { calls.incrementAndGet(); return "회의 결과"; }
            public String model() { return "legacy-model"; }
        };
        var result = legacy.transcribeWithUsage(Path.of("not-read.wav"), "name hint");
        assertThat(result.text()).isEqualTo("회의 결과");
        assertThat(result.usage().model()).isEqualTo("legacy-model");
        assertThat(result.usage().usageType()).isEqualTo("unavailable");
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().durationSeconds()).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }
}
