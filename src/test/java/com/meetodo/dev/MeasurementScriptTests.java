package com.meetodo.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class MeasurementScriptTests {
    @TempDir Path temp;
    private final String response = "{\"summary\":\"첫 줄\\n둘째 줄\",\"tasks\":[{\"title\":\"로그인 API 오류 처리\",\"assigneeTeam\":\"백엔드팀\"}],\"llmCallCount\":4,\"toolCallCount\":3,\"validationFailureCount\":0,\"processingTimeMs\":6365}";

    @Test void savesEscapedNewlinesAsValidUnmodifiedJson() throws Exception {
        // 실제 저장 한 줄만 실행해 zsh echo의 역슬래시 변환을 재현하며 DB·키체인·서버는 실행하지 않는다.
        String script = Files.readString(Path.of("measure.sh"));
        String save = script.lines().filter(line -> line.contains("\"$res\"") && line.contains(">")).findFirst().orElseThrow();
        Path output = temp.resolve("response.json");
        save = save.replace("/tmp/meetodo-${label}-${round}.json", "$MEASURE_TEST_OUTPUT");
        var process = new ProcessBuilder("zsh", "-f", "-c", "res=\"$MEASURE_TEST_RESPONSE\"\n" + save).redirectErrorStream(true);
        process.environment().put("MEASURE_TEST_RESPONSE", response);
        process.environment().put("MEASURE_TEST_OUTPUT", output.toString());
        var running = process.start();
        String diagnostics = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(running.waitFor()).as(diagnostics).isZero();
        String saved = Files.readString(output);
        assertThat(saved).contains("첫 줄\\n둘째 줄").isEqualTo(response);
        assertThat(new ObjectMapper().readTree(saved).path("summary").asText()).isEqualTo("첫 줄\n둘째 줄");
    }

    @Test void printsTaskTitleAlongsideOriginalMetrics() throws Exception {
        // 스크립트의 실제 표 출력 Python만 실행해 원래 지표와 제목 열을 함께 검증한다.
        String script = Files.readString(Path.of("measure.sh"));
        int start = script.indexOf("import json, sys\n");
        String python = script.substring(start, script.indexOf("\nPY", start));
        var running = new ProcessBuilder(List.of("python3", "-c", python, "백엔드팀지영", "1", response))
                .redirectErrorStream(true).start();
        String line = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(running.waitFor()).as(line).isZero();
        assertThat(line.strip()).isEqualTo("| 백엔드팀지영 | 1 | 백엔드팀 | 4 | 3 | 0 | 6365 | 로그인 API 오류 처리 |");
        assertThat(script).contains("| 회의록 | 회차 | 결과 | LLM | 도구 | 검증실패 | 시간(ms) | 제목 |");
    }
}
