package com.meetodo.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiSpeechTranscriberTests {
    @TempDir Path directory;
    MockRestServiceServer server;
    OpenAiSpeechTranscriber transcriber;
    Path file;

    @BeforeEach void setUp() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        transcriber = new OpenAiSpeechTranscriber(OpenAiAudioApi.builder()
                .apiKey(String.join("", "sk-", "unit-test-secret"))
                .restClientBuilder(builder).build(), "gpt-transcribe");
        file = Files.write(directory.resolve("sensitive-original-name.wav"), new byte[]{1, 2, 3});
    }

    @Test void sendsSupportedMultipartFieldsAndSanitizedFilename() {
        // 원본 파일명 없이 파일·모델·힌트와 JSON 응답 형식만 보낸다.
        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).contains("name=\"file\"; filename=\"meeting.wav\"", "name=\"model\"", "gpt-transcribe",
                            "name=\"prompt\"", "Jiyoung", "name=\"response_format\"", "json")
                            .doesNotContain("sensitive-original-name", "name=\"language\"", "name=\"temperature\"");
                }).andRespond(withSuccess("{\"text\":\"회의 결과\"}", MediaType.APPLICATION_JSON));
        assertThat(transcriber.transcribe(file, "Jiyoung")).isEqualTo("회의 결과");
        assertThat(transcriber.model()).isEqualTo("gpt-transcribe");
        server.verify();
    }

    @Test void ignoresExtraProviderResponseFields() {
        // 외부 API 응답은 여분 필드가 생겨도 전사문을 읽는다.
        server.expect(anything()).andRespond(withSuccess("{\"text\":\"hello\",\"usage\":{\"tokens\":5}}", MediaType.APPLICATION_JSON));
        assertThat(transcriber.transcribe(file, null)).isEqualTo("hello");
        server.verify();
    }

    @Test void recordsOfficialTokenUsageWithoutExposingExtraProviderFields() {
        // 공급자가 실제 반환한 입력·출력·총 토큰만 기록하고 여분 응답 필드는 무시한다.
        server.expect(anything()).andRespond(withSuccess("""
                {"text":"회의 결과","new_provider_field":"ignored","usage":{"type":"tokens",
                "input_tokens":14,"output_tokens":45,"total_tokens":59,
                "input_token_details":{"audio_tokens":14},"new_usage_field":"ignored"}}
                """, MediaType.APPLICATION_JSON));
        var result = transcriber.transcribeWithUsage(file, "Jiyoung");
        assertThat(result.text()).isEqualTo("회의 결과");
        assertThat(result.usage().model()).isEqualTo("gpt-transcribe");
        assertThat(result.usage().usageType()).isEqualTo("tokens");
        assertThat(result.usage().inputTokens()).isEqualTo(14L);
        assertThat(result.usage().outputTokens()).isEqualTo(45L);
        assertThat(result.usage().totalTokens()).isEqualTo(59L);
        assertThat(result.usage().durationSeconds()).isNull();
        assertThat(result.usage().estimatedCostUsd()).isNull();
        server.verify();
    }

    @Test void recordsOfficialDurationUsageInsteadOfRequestLatency() {
        // 소요 시간이 아닌 공급자 usage.seconds의 음성 길이를 소수 정밀도로 보존한다.
        server.expect(anything()).andRespond(withSuccess("""
                {"text":"회의 결과","usage":{"type":"duration","seconds":27.125}}
                """, MediaType.APPLICATION_JSON));
        var usage = transcriber.transcribeWithUsage(file, null).usage();
        assertThat(usage.usageType()).isEqualTo("duration");
        assertThat(usage.durationSeconds()).isEqualByComparingTo("27.125");
        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        server.verify();
    }

    @Test void missingProviderUsageStaysUnavailableInsteadOfZero() {
        // usage 미제공을 토큰0이나 파일 크기에서 계산한 사용량으로 바꾸지 않는다.
        server.expect(anything()).andRespond(withSuccess("{\"text\":\"회의 결과\"}", MediaType.APPLICATION_JSON));
        var result = transcriber.transcribeWithUsage(file, null);
        assertThat(result.text()).isEqualTo("회의 결과");
        assertThat(result.usage().usageType()).isEqualTo("unavailable");
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().outputTokens()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        assertThat(result.usage().durationSeconds()).isNull();
        server.verify();
    }

    @Test void unknownProviderUsageTypeDoesNotGuessUsageOrDiscardTranscript() {
        // 새로운 usage 형식은 전사문을 유지하면서 알 수 없음으로 기록한다.
        server.expect(anything()).andRespond(withSuccess("""
                {"text":"회의 결과","usage":{"type":"future_unit","seconds":5,"total_tokens":8}}
                """, MediaType.APPLICATION_JSON));
        var result = transcriber.transcribeWithUsage(file, null);
        assertThat(result.text()).isEqualTo("회의 결과");
        assertThat(result.usage().usageType()).isEqualTo("unavailable");
        assertThat(result.usage().durationSeconds()).isNull();
        assertThat(result.usage().totalTokens()).isNull();
        server.verify();
    }

    @Test void negativeProviderUsageDoesNotBecomeBillableUsage() {
        // 잘못된 음수 사용량은 비용 계산에 쓰지 않고 전사문만 유지한다.
        server.expect(anything()).andRespond(withSuccess("""
                {"text":"회의 결과","usage":{"type":"tokens","input_tokens":-1,"output_tokens":3,"total_tokens":2}}
                """, MediaType.APPLICATION_JSON));
        var result = transcriber.transcribeWithUsage(file, null);
        assertThat(result.text()).isEqualTo("회의 결과");
        assertThat(result.usage().usageType()).isEqualTo("unavailable");
        assertThat(result.usage().inputTokens()).isNull();
        server.verify();
    }

    @Test void explicitlyReportedZeroTokensRemainReportedUsage() {
        // 공급자가 명시한0은 미제공과 구분해 기록한다.
        server.expect(anything()).andRespond(withSuccess("""
                {"text":"회의 결과","usage":{"type":"tokens","input_tokens":0,"output_tokens":0,"total_tokens":0}}
                """, MediaType.APPLICATION_JSON));
        var usage = transcriber.transcribeWithUsage(file, null).usage();
        assertThat(usage.usageType()).isEqualTo("tokens");
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(usage.totalTokens()).isZero();
        server.verify();
    }

    @Test void serverFailureIsSanitizedAndNeverRetried(CapturedOutput output) {
        // 공급자 본문·키·원문을 예외와 로그에 남기지 않고 한 번만 요청한다.
        server.expect(anything()).andRespond(withServerError().body("provider-private-meeting-and-secret"));
        assertSafeFailure(() -> transcriber.transcribe(file, "private-member"));
        assertThat(output.getAll()).doesNotContain("provider-private-meeting-and-secret", "unit-test-secret", "private-member");
        server.verify();
    }

    @Test void timeoutIsSanitizedAndNeverRetried(CapturedOutput output) {
        // 네트워크 오류 원문의 민감한 URL도 호출자나 로그에 전달하지 않는다.
        server.expect(anything()).andRespond(withException(new java.net.SocketTimeoutException("private-provider-url")));
        assertSafeFailure(() -> transcriber.transcribe(file, null));
        assertThat(output.getAll()).doesNotContain("private-provider-url");
        server.verify();
    }

    @Test void malformedResponseIsSanitized(CapturedOutput output) {
        // 파싱 실패의 원본 응답 문자열도 예외 cause로 노출하지 않는다.
        server.expect(anything()).andRespond(withSuccess("private-malformed-payload", MediaType.APPLICATION_JSON));
        assertSafeFailure(() -> transcriber.transcribe(file, null));
        assertThat(output.getAll()).doesNotContain("private-malformed-payload");
        server.verify();
    }

    @Test void unreadableFileIsSanitizedWithoutCallingProvider() {
        // 파일 경로를 포함한 IOException을 외부로 전달하지 않는다.
        assertSafeFailure(() -> transcriber.transcribe(directory.resolve("missing-private.wav"), null));
        server.verify();
    }

    @Test void invalidModelMetadataIsRejected() {
        // 저장·응답에 노출될 모델명에 제어문자나 비밀 문자열을 넣지 못한다.
        assertThatThrownBy(() -> new OpenAiSpeechTranscriber(null, "gpt-transcribe\nprivate-secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid audio transcription model").hasNoCause();
    }

    private void assertSafeFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(IllegalStateException.class)
                .hasMessage("Audio transcription failed").hasNoCause();
    }
}
