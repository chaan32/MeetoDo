package com.meetodo.slack;

import com.meetodo.audio.AudioMeetingException;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.files.FilesInfoRequest;
import com.slack.api.methods.response.files.FilesInfoResponse;
import com.slack.api.model.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackAudioDownloaderTests {
    private final MethodsClient slack = mock(MethodsClient.class);
    private final HttpsURLConnection connection = mock(HttpsURLConnection.class);
    private final SlackAudioDownloader.ConnectionFactory connections = mock(SlackAudioDownloader.ConnectionFactory.class);
    private final File file = File.builder().id("F1").name("meeting.mp3").mode("hosted").size(3)
            .urlPrivateDownload("https://files.slack.com/files-pri/download/meeting.mp3").build();
    private final FilesInfoResponse metadata = new FilesInfoResponse();
    private SlackAudioDownloader downloader;

    @BeforeEach void setUp() throws Exception {
        metadata.setOk(true); metadata.setFile(file);
        when(slack.filesInfo(any(FilesInfoRequest.class))).thenReturn(metadata);
        when(connections.open(any())).thenReturn(connection);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        downloader = new SlackAudioDownloader(slack, "secret-token", connections);
    }

    @Test void downloadsFreshMetadataByIdWithBearerAndBoundedTimeouts() throws Exception {
        // 파일 이벤트의 URL 대신 files.info 결과만 사용하고 토큰은 허용된 호스트에만 보낸다.
        var result = downloader.download("F1");
        var request = ArgumentCaptor.forClass(FilesInfoRequest.class);
        verify(slack).filesInfo(request.capture());
        assertThat(request.getValue().getFile()).isEqualTo("F1");
        verify(connections).open(URI.create(file.getUrlPrivateDownload()));
        verify(connection).setRequestProperty("Authorization", "Bearer secret-token");
        verify(connection).setRequestMethod("GET");
        verify(connection).setConnectTimeout(3000);
        verify(connection).setReadTimeout(30000);
        verify(connection).setInstanceFollowRedirects(false);
        verify(connection).disconnect();
        assertThat(result.getOriginalFilename()).isEqualTo("audio.mp3");
        assertThat(result.getBytes()).containsExactly(1, 2, 3);
        assertThat(result.getInputStream().readAllBytes()).containsExactly(1, 2, 3);
        assertThat(result.getSize()).isEqualTo(3);
        assertThat(result.isEmpty()).isFalse();
    }
    @Test void fallsBackToPrivateUrl() throws Exception {
        // 다운로드 전용 URL이 없으면 같은 검증을 거쳐 private URL을 사용한다.
        file.setUrlPrivateDownload(null); file.setUrlPrivate("https://files.slack.com/private");
        downloader.download("F1");
        verify(connections).open(URI.create(file.getUrlPrivate()));
    }
    @Test void acceptsExplicitHttpsPort() {
        // HTTPS 기본 포트의 명시도 허용한다.
        file.setUrlPrivateDownload("https://files.slack.com:443/private");
        assertThat(downloader.download("F1").getSize()).isEqualTo(3);
    }
    @Test void rejectsForeignHost() { // 호스트 접두사가 같아도 다른 도메인에 자격증명을 보내지 않는다.
        invalidUrl("https://files.slack.com.attacker.example/file");
    }
    @Test void rejectsHttp() { // 평문 연결에 토큰을 보내지 않는다.
        invalidUrl("http://files.slack.com/file");
    }
    @Test void rejectsUserInfo() { // URL 사용자 정보를 허용하지 않는다.
        invalidUrl("https://user@files.slack.com/file");
    }
    @Test void rejectsAlternativePort() { // 기본 HTTPS 포트 이외 연결을 막는다.
        invalidUrl("https://files.slack.com:8443/file");
    }
    @Test void rejectsMalformedUrl() { // URL 파싱 예외 원문을 노출하지 않는다.
        invalidUrl("https://files.slack.com/invalid path?secret-token");
    }
    @Test void rejectsMissingUrl() { // 다운로드 주소가 없으면 외부 요청하지 않는다.
        file.setUrlPrivateDownload(null); invalid("SLACK_FILE_DOWNLOAD_FAILED"); verifyNoInteractions(connections);
    }
    @Test void rejectsExternalFlag() { // 원격 파일은 메타데이터 플래그만으로도 거절한다.
        file.setExternal(true); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsExternalMode() { // 원격 모드도 별도로 거절한다.
        file.setMode("external"); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsRemoteFiletype() { // 원격 파일 유형도 별도로 거절한다.
        file.setFiletype("remote"); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsUnsupportedFilenameDespiteAudioMime() { // 명시된 잘못된 확장자를 MIME으로 덮어쓰지 않는다.
        file.setName("payload.exe"); file.setMimetype("audio/mpeg"); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void derivesUnnamedClipFromMime() { // 이름 없는 음성 클립만 지원 MIME으로 파일명을 만든다.
        file.setName(null); file.setMimetype("audio/mp4");
        assertThat(downloader.download("F1").getOriginalFilename()).isEqualTo("audio.m4a");
    }
    @Test void derivesUnnamedClipFromSupportedFiletype() { // 이름 없는 클립의 지원 filetype도 허용한다.
        file.setName(""); file.setFiletype("ogg");
        assertThat(downloader.download("F1").getOriginalFilename()).isEqualTo("audio.ogg");
    }
    @Test void derivesExtensionlessClipFromFiletype() { // 확장자 없는 음성 클립 이름은 지원 filetype으로 보완한다.
        file.setName("audio_message"); file.setFiletype("webm");
        assertThat(downloader.download("F1").getOriginalFilename()).isEqualTo("audio.webm");
    }
    @Test void derivesExtensionlessClipFromMime() { // filetype이 없으면 확장자 없는 클립에 지원 MIME을 사용한다.
        file.setName("audio_message"); file.setMimetype("audio/webm");
        assertThat(downloader.download("F1").getOriginalFilename()).isEqualTo("audio.webm");
    }
    @Test void rejectsExtensionlessUnknownType() { // 확장자와 지원 메타데이터 모두 없으면 거절한다.
        file.setName("audio_message"); file.setFiletype("unknown"); file.setMimetype("application/octet-stream");
        invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsUnnamedUnknownType() { // 확장자를 판단할 수 없으면 추측하지 않는다.
        file.setName(null); file.setMimetype("application/octet-stream"); invalid("INVALID_AUDIO");
    }
    @Test void rejectsOversizedMetadata() { // 25MB 초과 메타데이터는 다운로드 전에 거절한다.
        file.setSize(25_000_001); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsZeroMetadataSize() { // 빈 파일의 메타데이터도 거절한다.
        file.setSize(0); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void rejectsMissingMetadataSize() { // 누락된 크기는 안전하게 거절한다.
        file.setSize(null); invalid("INVALID_AUDIO"); verifyNoInteractions(connections);
    }
    @Test void acceptsExactSizeBoundary() throws Exception { // 상한과 같은 실제 크기는 허용한다.
        file.setSize(25_000_000);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[25_000_000]));
        assertThat(downloader.download("F1").getSize()).isEqualTo(25_000_000);
    }
    @Test void rejectsActualOverflowAndStopsAfterLimitPlusOne() throws Exception {
        // 메타데이터가 작아도 스트림은 상한+1까지만 읽고 닫는다.
        var stream = spy(new ByteArrayInputStream(new byte[25_000_010]));
        when(connection.getInputStream()).thenReturn(stream);
        invalid("INVALID_AUDIO");
        assertThat(stream.available()).isEqualTo(9);
        verify(stream).close(); verify(connection).disconnect();
    }
    @Test void rejectsActuallyEmptyStream() throws Exception { // 크기 메타데이터와 무관하게 실제 빈 파일은 거절한다.
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0])); invalid("INVALID_AUDIO");
    }
    @Test void rejectsRedirectWithoutReadingOrRetrying() throws Exception { // 리다이렉트 대상에 토큰을 전달하지 않는다.
        when(connection.getResponseCode()).thenReturn(302); invalid("SLACK_FILE_DOWNLOAD_FAILED");
        verify(connection, never()).getInputStream(); verify(connection).disconnect(); verify(connections, times(1)).open(any());
    }
    @Test void rejectsHttpFailureWithoutReadingBody() throws Exception { // 오류 응답 본문을 읽거나 노출하지 않는다.
        when(connection.getResponseCode()).thenReturn(403); invalid("SLACK_FILE_DOWNLOAD_FAILED");
        verify(connection, never()).getInputStream();
    }
    @Test void sanitizesSdkFailure() throws Exception { // SDK 예외 메시지와 cause에 비밀을 보존하지 않는다.
        when(slack.filesInfo(any(FilesInfoRequest.class))).thenThrow(new IOException("secret-token private-url"));
        invalid("SLACK_FILE_DOWNLOAD_FAILED"); verifyNoInteractions(connections);
    }
    @Test void rejectsSlackErrorResponse() { // Slack 실패 응답 문자열을 외부로 전달하지 않는다.
        metadata.setOk(false); metadata.setError("secret-token"); invalid("SLACK_FILE_DOWNLOAD_FAILED");
    }
    @Test void rejectsMissingFileMetadata() { // 성공 응답의 파일 누락도 일반화된 오류로 처리한다.
        metadata.setFile(null); invalid("SLACK_FILE_DOWNLOAD_FAILED");
    }
    @Test void sanitizesReadFailureAndClosesResources() throws Exception { // 스트림 실패 시 연결과 스트림을 정리한다.
        var stream = mock(InputStream.class);
        when(stream.readNBytes(25_000_001)).thenThrow(new IOException("secret-token private-url"));
        when(connection.getInputStream()).thenReturn(stream); invalid("SLACK_FILE_DOWNLOAD_FAILED");
        verify(stream).close(); verify(connection).disconnect();
    }
    @Test void sanitizesDisconnectFailure() { // 연결 종료 예외도 원문과 cause 없이 일반화한다.
        doThrow(new IllegalStateException("secret-token private-url")).when(connection).disconnect();
        invalid("SLACK_FILE_DOWNLOAD_FAILED");
    }
    private void invalidUrl(String url) {
        file.setUrlPrivateDownload(url); invalid("SLACK_FILE_DOWNLOAD_FAILED"); verifyNoInteractions(connections);
    }
    private void invalid(String code) {
        assertThatThrownBy(() -> downloader.download("F1")).isInstanceOfSatisfying(AudioMeetingException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain("secret-token", "private-url", "files.slack.com");
            assertThat(failure.getCause()).isNull();
        });
    }
}
