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
