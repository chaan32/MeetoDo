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
    private final String response = "{\"status\":\"DONE\",\"summary\":\"첫 줄\\n둘째 줄\",\"tasks\":[{\"title\":\"로그인 API 오류 처리\",\"assigneeTeam\":\"백엔드팀\",\"dueDate\":\"2026-11-20\"}],\"llmCallCount\":4,\"toolCallCount\":3,\"validationFailureCount\":0,\"processingTimeMs\":6365}";

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
        String line = report(response, "2026-11-20", "2026-11-13");
        assertThat(line.strip()).isEqualTo("| 백엔드팀지영 | 1 | 백엔드팀 | 4 | 3 | 0 | 6365 | 로그인 API 오류 처리 | 2026-11-13 | 2026-11-20 | 2026-11-20 | 예 |");
        assertThat(script).contains("| 회의록 | 회차 | 결과 | LLM | 도구 | 검증실패 | 시간(ms) | 제목 |");
    }
    @Test void calculatesExpectedNextFridayFromCapturedSeoulDate() throws Exception {
        // G 상대 날짜의 기대값을 서버와 독립된 달력 계산으로 비교한다.
        assertThat(report(response, "다음 주 금요일", "2026-11-13")).endsWith("| 2026-11-20 | 2026-11-20 | 예 |\n");
    }

    @Test void reportsObservedWrongTuesdayAsMismatch() throws Exception {
        // 실제 개선 전 날짜2026-11-24는 다음 주 금요일의 정답과 다르다.
        assertThat(report(response.replace("2026-11-20", "2026-11-24"), "다음 주 금요일", "2026-11-13"))
                .endsWith("| 2026-11-20 | 2026-11-24 | 아니오 |\n");
    }

    @Test void calculatesSundayForEndOfThisWeek() throws Exception {
        // 이번 주 안에의 기대값은 해당 주 일요일이다.
        assertThat(report(response, "이번 주 안에", "2026-11-13")).contains("| 2026-11-15 | 2026-11-20 | 아니오 |");
    }

    @Test void calculatesMondayForNextWeek() throws Exception {
        // 다음 주 월요일의 기대값을 주 시작에서 계산한다.
        assertThat(report(response, "다음 주 월요일", "2026-11-13")).contains("| 2026-11-16 | 2026-11-20 | 아니오 |");
    }

    @Test void calculatesTomorrowFromReferenceDate() throws Exception {
        // 내일은 기준일의 다음 날이다.
        assertThat(report(response, "내일", "2026-11-13")).contains("| 2026-11-14 | 2026-11-20 | 아니오 |");
    }

    @Test void midnightCrossingRequiresRemeasurement() throws Exception {
        // 요청 전후 서울 날짜가 바뀌면 단순 불일치로 기록하지 않는다.
        assertThat(report(response, "내일", "2026-11-14")).contains("기준일 변경: 재측정");
    }

    @Test void failedResponseIsNeverConsideredDateMatch() throws Exception {
        // 실패 응답의 빈 tasks와 마감일 없음은 정답으로 집계하지 않는다.
        assertThat(report("{\"status\":\"FAILED\",\"tasks\":[]}", "없음", "2026-11-13")).endsWith("| 없음 | 없음 | 아니오 |\n");
    }

    private String report(String raw, String expression, String finished) throws Exception {
        String script = Files.readString(Path.of("measure.sh"));
        int start = script.indexOf("import json, sys\n");
        String python = script.substring(start, script.indexOf("\nPY", start));
        var running = new ProcessBuilder(List.of("python3", "-c", python, "백엔드팀지영", "1", raw,
                expression, "2026-11-13", finished)).redirectErrorStream(true).start();
        String line = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(running.waitFor()).as(line).isZero();
        return line;
    }

}
