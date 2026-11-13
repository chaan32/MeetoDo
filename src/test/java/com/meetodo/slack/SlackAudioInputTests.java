package com.meetodo.slack;

import com.meetodo.audio.*;
import com.meetodo.meeting.*;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.notification.NotificationStatus;
import com.slack.api.bolt.App;
import com.slack.api.bolt.handler.BoltEventHandler;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.File;
import com.slack.api.model.BotProfile;
import com.slack.api.model.event.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SlackAudioInputTests {
    final MethodsClient client = mock(MethodsClient.class);
    final SlackAudioDownloader downloader = mock(SlackAudioDownloader.class);
    final AudioMeetingService audio = mock(AudioMeetingService.class);
    final SlackAudioUploadStore store = mock(SlackAudioUploadStore.class);
    final SlackBotService bot = mock(SlackBotService.class);
    final List<Runnable> queue = new ArrayList<>();
    SlackAudioInputService input;
    final MockMultipartFile file = new MockMultipartFile("file", "meeting.wav", "audio/wav", new byte[]{1});
    @BeforeEach void setup() throws Exception {
        input = new SlackAudioInputService(client, downloader, audio, store, queue::add, bot);
        ChatPostMessageResponse posted = new ChatPostMessageResponse(); posted.setOk(true); posted.setTs("2.0");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(posted);
        when(store.find(anyString())).thenReturn(Optional.empty()); when(store.claim(anyString())).thenReturn(true);
        when(downloader.download(anyString())).thenReturn(file);
        when(bot.prepareInput(anyString(), anyString())).thenAnswer(call -> new SlackBotService.InputPublication(call.getArgument(0), call.getArgument(1), call.getArgument(0), call.getArgument(1)));
        when(audio.submitFromSlack(any(), any(), any())).thenReturn(result());
    }
    private AudioMeetingResponse result() {
        return new AudioMeetingResponse("private transcript", "model", 1L,
                new MeetingResponse(7L, null, MeetingStatus.DONE, "", List.of(), 0, 1L, 1, 1, 0,
                        NotificationStatus.SENT, "slack-bot", null, null));
    }
    private MessageEvent dm() {
        MessageEvent event = new MessageEvent(); event.setChannel("D1"); event.setChannelType("im"); event.setUser("U1");
        event.setTs("1.0"); event.setFiles(List.of(File.builder().id("F1").build())); return event;
    }
    private void run() { queue.forEach(Runnable::run); }
    private String updatedText() throws Exception {
        var capture = ArgumentCaptor.forClass(ChatUpdateRequest.class); verify(client).chatUpdate(capture.capture());
        assertThat(capture.getValue().getChannel()).isEqualTo("D1"); assertThat(capture.getValue().getTs()).isEqualTo("2.0");
        return capture.getValue().getText();
    }
    @Test void directMessageQueuesBeforeAnyHttpOrStoreAccess() {
        // DM 입력 수신은 HTTP나 DB 접근 없이 실행 큐에 넣는다.
        input.accept(dm()); assertThat(queue).hasSize(1); verifyNoInteractions(client, store, downloader, audio);
    }
    @Test void progressIsPostedBeforeClaimAndDownloadInOriginalThread() throws Exception {
        // 원래 스레드에 진행 메시지가 게시된 뒤에만 파일을 선점하고 다운로드한다.
        var event = dm(); event.setThreadTs("0.5"); input.accept(event); run();
        var order = inOrder(client, store, downloader);
        var post = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        order.verify(client).chatPostMessage(post.capture()); order.verify(store).find("F1"); order.verify(store).claim("F1"); order.verify(downloader).download("F1");
        assertThat(post.getValue().getThreadTs()).isEqualTo("0.5"); assertThat(post.getValue().getText()).isEqualTo("⏳ 음성 변환 중...");
    }
    @Test void newMeetingReceivesProgressCoordinatesAndUsesSharedNotifier() throws Exception {
        // 새 회의에만 알림 좌표를 붙이고 완료 알림과 DM은 공통 경로에 맡긴다.
        Meeting meeting = Meeting.create("private transcript", MeetingSource.AUDIO);
        when(audio.submitFromSlack(any(), any(), any())).thenAnswer(call -> { Consumer<Meeting> hook = call.getArgument(1); hook.accept(meeting); return result(); });
        input.accept(dm()); run();
        assertThat(meeting.getSlackChannelId()).isEqualTo("D1"); assertThat(meeting.getSlackMessageTs()).isEqualTo("2.0");
        verify(store).finish("F1", 7L, null); verify(client, never()).chatUpdate(any(ChatUpdateRequest.class));
        verify(client, times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
    }
    @Test void contentDuplicateReplacesOwnProgressWithoutMutatingExistingMeeting() throws Exception {
        // 본문 해시 중복은 새 회의 훅 없이 자신의 진행 메시지만 기존 회의 안내로 바꾼다.
        input.accept(dm()); run(); assertThat(updatedText()).isEqualTo("이미 정리된 음성입니다 (회의 7)"); verify(store).finish("F1", 7L, null);
    }
    @Test void repeatedFileWithinOneEventIsQueuedOnce() {
        // 한 이벤트의 중복 파일 ID는 한 번만 처리하고 서로 다른 파일은 각각 처리한다.
        var event = dm(); event.setFiles(List.of(File.builder().id("F1").build(), File.builder().id("F1").build(), File.builder().id("F2").build()));
        input.accept(event); run(); assertThat(queue).hasSize(2); verify(downloader).download("F1"); verify(downloader).download("F2");
    }
    private SlackAudioUpload previous(SlackAudioUpload.Status status) {
        var previous = new SlackAudioUpload("F1", java.time.Instant.parse("2026-11-13T00:00:00Z"));
        if (status == SlackAudioUpload.Status.DONE) previous.finish(7L, null);
        if (status == SlackAudioUpload.Status.FAILED) previous.finish(null, "AUDIO_TRANSCRIPTION_FAILED");
        return previous;
    }
    @Test void completedUploadIsNotDownloadedAgain() throws Exception {
        // 이미 처리된 파일의 재전송은 다운로드와 STT 없이 이전 회의 ID를 알려준다.
        when(store.find("F1")).thenReturn(Optional.of(previous(SlackAudioUpload.Status.DONE)));
        input.accept(dm()); run(); assertThat(updatedText()).contains("회의 7"); verifyNoInteractions(downloader, audio); verify(store, never()).claim(anyString());
    }
    @Test void processingUploadDoesNotStartSecondTranscription() throws Exception {
        // 처리 중 파일을 재수신해도 두 번째 STT를 실행하지 않는다.
        when(store.find("F1")).thenReturn(Optional.of(previous(SlackAudioUpload.Status.PROCESSING)));
        input.accept(dm()); run(); assertThat(updatedText()).contains("처리 중"); verifyNoInteractions(downloader, audio);
    }
    @Test void failedUploadShowsSafePreviousFailureWithoutAutomaticRetry() throws Exception {
        // 실패한 파일은 이전 실패를 안내하고 자동 재처리하지 않는다.
        when(store.find("F1")).thenReturn(Optional.of(previous(SlackAudioUpload.Status.FAILED)));
        input.accept(dm()); run(); assertThat(updatedText()).contains("이전 처리").contains("실패"); verifyNoInteractions(downloader, audio);
    }
    @Test void concurrentClaimLoserReplacesOwnProgressWithExistingState() throws Exception {
        // 선점 경쟁에서 진 요청은 자신의 진행 메시지를 수정하고 다운로드하지 않는다.
        when(store.find("F1")).thenReturn(Optional.empty(), Optional.of(previous(SlackAudioUpload.Status.PROCESSING)));
        when(store.claim("F1")).thenReturn(false); input.accept(dm()); run(); assertThat(updatedText()).contains("처리 중"); verifyNoInteractions(downloader, audio);
    }
    @Test void missingChannelMembershipNeverClaimsFile() throws Exception {
        // 진행 메시지를 게시할 수 없는 채널에서는 파일 선점과 회의 생성을 하지 않는다.
        ChatPostMessageResponse denied = new ChatPostMessageResponse(); denied.setError("not_in_channel");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(denied);
        input.accept(dm()); run(); verifyNoInteractions(store, downloader, audio);
        var notice = ArgumentCaptor.forClass(ChatPostEphemeralRequest.class); verify(client).chatPostEphemeral(notice.capture()); assertThat(notice.getValue().getText()).contains("/invite @MeetoDo");
    }
    @Test void downloadFailureMarksUploadFailedAndReplacesProgress() throws Exception {
        // 다운로드 실패는 새 회의 없이 파일 실패 기록과 안전한 안내만 남긴다.
        when(downloader.download("F1")).thenThrow(new AudioMeetingException(HttpStatus.BAD_REQUEST, "INVALID_AUDIO", "private failure"));
        input.accept(dm()); run(); verify(store).finish("F1", null, "INVALID_AUDIO"); assertThat(updatedText()).contains("음성"); verifyNoInteractions(audio);
    }
    @Test void transcriptionFailureMarksUploadFailedWithoutMeeting() throws Exception {
        // 전사 실패로 회의를 생성하지 못한 경우에도 진행 메시지를 완료 상태로 바꾼다.
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new AudioMeetingException(HttpStatus.BAD_GATEWAY, "AUDIO_TRANSCRIPTION_FAILED", "private failure"));
        input.accept(dm()); run(); verify(store).finish("F1", null, "AUDIO_TRANSCRIPTION_FAILED"); assertThat(updatedText()).contains("음성");
    }
    @Test void meetingFailureKeepsSharedFailureNotificationAndMeetingId() throws Exception {
        // 회의 실패 알림은 공통 AFTER_COMMIT 경로가 수정하므로 중복 게시하지 않는다.
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new MeetingProcessingException(7L));
        input.accept(dm()); run(); verify(store).finish("F1", 7L, "MEETING_PROCESSING_FAILED"); verify(client, never()).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void unexpectedFailureNeverLeaksCredentialsOrProviderPayload(CapturedOutput output) throws Exception {
        // 임의 공급자 예외의 비밀값은 로그와 진행 메시지에 노출하지 않는다.
        String secret = String.join("", "xox", "b-private https", "://private/secret transcript");
        when(downloader.download("F1")).thenThrow(new IllegalStateException(secret)); input.accept(dm()); run();
        assertThat(updatedText()).doesNotContain(secret, "transcript"); assertThat(output).doesNotContain(secret, "transcript"); verify(store).finish("F1", null, "SLACK_AUDIO_FAILED");
    }
    @Test void botMessageIsIgnored() {
        // 봇 ID가 포함된 메시지는 파일이 있어도 처리하지 않는다.
        var event = dm(); event.setBotId("B1"); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void botProfileMessageIsIgnored() {
        // 봇 프로필만 포함된 메시지도 다시 입력으로 소비하지 않는다.
        var event = dm(); event.setBotProfile(new BotProfile()); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void editedMessageIsIgnored() {
        // 편집 이벤트는 기존 파일을 재처리하지 않는다.
        var event = dm(); event.setEdited(new MessageEvent.Edited()); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void nonDmMessageIsIgnored() {
        // 멘션 없는 채널 메시지는 처리 대상이 아니다.
        var event = dm(); event.setChannel("C1"); event.setChannelType("channel"); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void inconsistentChannelTypeIsIgnored() {
        // channel_type과 실제 채널 접두사가 모두 DM 조건을 충족해야 한다.
        var event = dm(); event.setChannelType("channel"); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void messageWithoutFilesIsIgnored() {
        // 일반 텍스트 DM은 음성 처리를 시작하지 않는다.
        var event = dm(); event.setFiles(null); input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void channelMentionAcceptsFilesInOriginalThread() throws Exception {
        // 플랫폼에서 봇 멘션으로 전달된 채널 이벤트는 기존 스레드를 보존한다.
        AppMentionEvent event = new AppMentionEvent(); event.setChannel("G1"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles());
        input.accept(event); run(); var post = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client).chatPostMessage(post.capture()); assertThat(post.getValue().getThreadTs()).isEqualTo("1.0");
    }
    @Test void editedMentionIsIgnored() {
        // 멘션 편집도 파일을 재처리하지 않는다.
        AppMentionEvent event = new AppMentionEvent(); event.setChannel("C1"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles()); event.setEdited(new AppMentionEvent.Edited());
        input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void fileShareSubtypeInDirectMessageIsAccepted() {
        // SDK가 일반 MessageEvent와 별도로 디스패치하는 file_share subtype을 처리한다.
        MessageFileShareEvent event = new MessageFileShareEvent(); event.setChannel("D1"); event.setChannelType("im"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles());
        input.accept(event); assertThat(queue).hasSize(1);
    }
    @Test void registersSdkMessageFileShareAndMentionHandlers() {
        // SDK의 일반 메시지·파일 공유 subtype·앱 멘션 각각에 핸들러를 등록한다.
        App app = mock(App.class); input.register(app);
        verify(app).event(eq(MessageEvent.class), any(BoltEventHandler.class));
        verify(app).event(eq(MessageFileShareEvent.class), any(BoltEventHandler.class));
        verify(app).event(eq(AppMentionEvent.class), any(BoltEventHandler.class));
    }
    @Test void socketFileSharePayloadActuallyReachesRegisteredHandler() {
        // 실제 Bolt subtype 디스패치에서 DM 파일 공유가 음성 입력 큐까지 연결된다.
        App app = new App(com.slack.api.bolt.AppConfig.builder().singleTeamBotToken("test-token").build());
        input.register(app);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "middlewareList", new ArrayList<>());
        new SlackSocketDispatch(app, Runnable::run).receive(mock(com.slack.api.socket_mode.SocketModeClient.class), """
                {"type":"events_api","envelope_id":"audio-file-share","payload":{"type":"event_callback",
                 "team_id":"T1","api_app_id":"A1","event":{"type":"message","subtype":"file_share",
                 "channel":"D1","channel_type":"im","user":"U1","ts":"1.0","files":[{"id":"F1"}]}}}
                """);
        assertThat(queue).hasSize(1); verifyNoInteractions(client, store, downloader, audio);
    }
    @Test void socketMessageChangedPayloadDoesNotReachAudioHandler() {
        // 수정 subtype은 파일 공유 핸들러로 들어가지 않아 기존 파일을 재처리하지 않는다.
        App app = new App(com.slack.api.bolt.AppConfig.builder().singleTeamBotToken("test-token").build());
        input.register(app);
        org.springframework.test.util.ReflectionTestUtils.setField(app, "middlewareList", new ArrayList<>());
        new SlackSocketDispatch(app, Runnable::run).receive(mock(com.slack.api.socket_mode.SocketModeClient.class), """
                {"type":"events_api","envelope_id":"audio-edited","payload":{"type":"event_callback",
                 "team_id":"T1","api_app_id":"A1","event":{"type":"message","subtype":"message_changed",
                 "channel":"D1","channel_type":"im","message":{"type":"message","user":"U1",
                 "ts":"1.0","files":[{"id":"F1"}]}}}}
                """);
        assertThat(queue).isEmpty(); verifyNoInteractions(client, store, downloader, audio);
    }
    @Test void channelNotFoundNeverClaimsFile() throws Exception {
        // 접근할 수 없는 채널도 초대 안내를 시도하고 DB나 파일에 접근하지 않는다.
        ChatPostMessageResponse denied = new ChatPostMessageResponse(); denied.setError("channel_not_found");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(denied);
        input.accept(dm()); run(); verifyNoInteractions(store, downloader, audio);
        verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void networkPostFailureNeverClaimsFileOrLeaksException(CapturedOutput output) throws Exception {
        // 진행 게시 자체의 예외에는 자격 증명과 서버 응답이 포함되어도 로그에 남기지 않는다.
        String secret = String.join("", "xox", "b-secret private-response");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenThrow(new java.io.IOException(secret));
        input.accept(dm()); run(); verifyNoInteractions(store, downloader, audio); assertThat(output).doesNotContain(secret);
    }
    @Test void disabledTranscriptionShowsSafeReasonWithoutCreatingMeeting() throws Exception {
        // OpenAI 비활성 상태는 내부 예외 대신 기능 상태만 알려준다.
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new AudioMeetingException(HttpStatus.NOT_FOUND, "AUDIO_TRANSCRIPTION_DISABLED", "private failure"));
        input.accept(dm()); run(); verify(store).finish("F1", null, "AUDIO_TRANSCRIPTION_DISABLED"); assertThat(updatedText()).contains("비활성화");
    }
    @Test void invalidTranscriptShowsSafeReasonWithoutCreatingMeeting() throws Exception {
        // 빈 전사나 길이 초과의 회의 생성 전 실패를 안전하게 안내한다.
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new AudioMeetingException(HttpStatus.BAD_REQUEST, "INVALID_TRANSCRIPT", "private transcript"));
        input.accept(dm()); run(); verify(store).finish("F1", null, "INVALID_TRANSCRIPT"); assertThat(updatedText()).contains("유효한 회의록");
    }
    @Test void unrecognizedAudioErrorCodeIsNeverPersistedOrLogged(CapturedOutput output) throws Exception {
        // 타입이 알려져 있어도 임의 코드 문자열은 고정된 안전 코드로 바꾼다.
        String secret = String.join("", "xox", "b-secret");
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new AudioMeetingException(HttpStatus.BAD_GATEWAY, secret, secret));
        input.accept(dm()); run(); verify(store).finish("F1", null, "SLACK_AUDIO_FAILED"); assertThat(updatedText()).doesNotContain(secret); assertThat(output).doesNotContain(secret);
    }
    @Test void botFileShareIsIgnored() {
        // 별도 subtype의 display_as_bot 메시지도 음성 입력에서 제외한다.
        MessageFileShareEvent event = new MessageFileShareEvent(); event.setChannel("D1"); event.setChannelType("im"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles()); event.setDisplayAsBot(true);
        input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void unmentionedChannelFileShareIsIgnored() {
        // 일반 채널의 파일 공유는 별도의 봇 멘션 이벤트가 있어야 처리한다.
        MessageFileShareEvent event = new MessageFileShareEvent(); event.setChannel("C1"); event.setChannelType("channel"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles());
        input.accept(event); assertThat(queue).isEmpty();
    }
    @Test void directMessageMentionEventIsIgnored() {
        // DM 입력은 message.im 경로로만 받아 멘션 이벤트와 중복 실행하지 않는다.
        AppMentionEvent event = new AppMentionEvent(); event.setChannel("D1"); event.setUser("U1"); event.setTs("1.0"); event.setFiles(dm().getFiles());
        input.accept(event); assertThat(queue).isEmpty();
    }

    @Test void concurrentContentHashLoserReplacesOwnProgressAfterDiscard() throws Exception {
        // 서로 다른 파일의 동일 전사문 저장 경쟁에서 진 임시 회의의 진행 메시지도 정리한다.
        Meeting candidate = Meeting.create("private transcript", MeetingSource.AUDIO);
        when(audio.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> beforeSave = call.getArgument(1); Consumer<Meeting> discard = call.getArgument(2);
            beforeSave.accept(candidate); discard.accept(candidate); return result();
        });
        input.accept(dm()); run();
        assertThat(updatedText()).isEqualTo("이미 정리된 음성입니다 (회의 7)"); verify(store).finish("F1", 7L, null);
    }
    @Test void failedUploadFinishStillReplacesDuplicateProgress(CapturedOutput output) throws Exception {
        // 파일 상태 저장 실패의 내부 예외는 감추되 이미 끝난 회의의 진행 메시지는 정리한다.
        String secret = String.join("", "xox", "b-private database body");
        doThrow(new IllegalStateException(secret)).when(store).finish("F1", 7L, null);
        input.accept(dm()); run(); assertThat(updatedText()).contains("회의 7"); assertThat(output).contains("UPLOAD_FINISH_FAILED").doesNotContain(secret);
        verify(store, never()).finish("F1", null, "SLACK_AUDIO_FAILED");
    }

    @SuppressWarnings("unchecked")
    @Test void registeredMessageHandlerAcknowledgesBeforeQueueing() throws Exception {
        // 등록된 핸들러도 이벤트 수신 확인을 먼저 호출한 뒤 파일 작업을 큐에 넣는다.
        List<String> order = new ArrayList<>();
        input = new SlackAudioInputService(client, downloader, audio, store, task -> { order.add("queued"); queue.add(task); }, bot);
        App app = mock(App.class); input.register(app);
        ArgumentCaptor<BoltEventHandler<MessageEvent>> handler = ArgumentCaptor.forClass(BoltEventHandler.class);
        verify(app).event(eq(MessageEvent.class), handler.capture());
        var payload = new com.slack.api.app_backend.events.payload.MessagePayload(); payload.setEvent(dm());
        var context = mock(com.slack.api.bolt.context.builtin.EventContext.class);
        doAnswer(call -> { order.add("ack"); return null; }).when(context).ack();
        handler.getValue().apply(payload, context); assertThat(order).containsExactly("ack", "queued");
        verifyNoInteractions(client, store, downloader, audio);
    }

    @Test void sameEventDeliveredTwiceOnlyDownloadsAndTranscribesOnce() throws Exception {
        // 같은 이벤트의 재전송도 파일 ID의 완료 기록으로 전사를 한 번만 실행한다.
        var completed = previous(SlackAudioUpload.Status.DONE);
        when(store.find("F1")).thenReturn(Optional.empty(), Optional.of(completed));
        var event = dm(); input.accept(event); input.accept(event); run();
        verify(downloader, times(1)).download("F1");
        verify(audio, times(1)).submitFromSlack(any(), any(), any());
        verify(store, times(1)).claim("F1");
    }

    @Test void sameFileSharedInAnotherMessageDoesNotTranscribeAgain() throws Exception {
        // 이벤트 시각이 다른 재공유도 같은 file ID라면 기존 회의를 안내한다.
        var completed = previous(SlackAudioUpload.Status.DONE);
        when(store.find("F1")).thenReturn(Optional.empty(), Optional.of(completed));
        var first = dm(); var reshared = dm(); reshared.setTs("3.0");
        input.accept(first); input.accept(reshared); run();
        verify(downloader, times(1)).download("F1");
        verify(audio, times(1)).submitFromSlack(any(), any(), any());
        var updates = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client, times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).allSatisfy(update -> assertThat(update.getText()).contains("회의 7"));
    }

    @Test void slackUpdateErrorResponseIsLoggedWithoutProviderText(CapturedOutput output) throws Exception {
        // HTTP200이어도 Slack ok=false인 진행 안내 실패를 고정 코드로 확인한다.
        var denied = new com.slack.api.methods.response.chat.ChatUpdateResponse();
        denied.setOk(false); denied.setError("private-download-url");
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(denied);
        input.accept(dm()); run();
        assertThat(output).contains("PROGRESS_UPDATE_FAILED").doesNotContain("private-download-url");
    }

    @Test void dmAudioPreparesTeamPublicationBeforeClaimAndAttachesTeamCoordinates() throws Exception {
        // DM 입력은 팀 채널 게시가 확인된 뒤에만 파일을 선점하고 새 회의 결과 좌표를 붙인다.
        var publication = new SlackBotService.InputPublication("D1", "2.0", "CTEAM", "3.0");
        when(bot.prepareInput("D1", "2.0")).thenReturn(publication);
        Meeting meeting = Meeting.create("private transcript", MeetingSource.SLACK_AUDIO);
        when(audio.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> hook = call.getArgument(1); hook.accept(meeting); return result();
        });
        input.accept(dm()); run();
        var order = inOrder(client, store, bot, downloader);
        order.verify(client).chatPostMessage(any(ChatPostMessageRequest.class)); order.verify(store).find("F1");
        order.verify(bot).prepareInput("D1", "2.0"); order.verify(store).claim("F1"); order.verify(downloader).download("F1");
        assertThat(meeting.getSlackChannelId()).isEqualTo("CTEAM"); assertThat(meeting.getSlackMessageTs()).isEqualTo("3.0");
        verify(bot, never()).discardInputPublication(any()); verify(client, never()).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void teamInvitationFailureUpdatesOriginWithoutClaimingAudio() throws Exception {
        // 팀 채널에 봇이 없으면 원래 DM만 초대 안내로 바꾸고 다운로드나 회의 생성을 하지 않는다.
        when(bot.prepareInput("D1", "2.0")).thenThrow(new com.meetodo.notification.NotificationDeliveryException("not_in_channel"));
        input.accept(dm()); run(); assertThat(updatedText()).contains("/invite @MeetoDo");
        verify(store, never()).claim(anyString()); verifyNoInteractions(downloader, audio);
    }
    @Test void repeatedDmFileDoesNotPrepareTeamPublication() throws Exception {
        // 파일 ID 중복은 팀 채널에 임시 메시지도 만들지 않는다.
        when(store.find("F1")).thenReturn(Optional.of(previous(SlackAudioUpload.Status.DONE)));
        input.accept(dm()); run(); assertThat(updatedText()).contains("회의 7"); verify(bot, never()).prepareInput(anyString(), anyString());
    }
    @Test void dmContentDuplicateDiscardsOnlyTeamProgressAndUpdatesOrigin() throws Exception {
        // 같은 전사문은 예약한 팀 메시지를 지우고 원래 DM에서만 중복 안내한다.
        var publication = new SlackBotService.InputPublication("D1", "2.0", "CTEAM", "3.0");
        when(bot.prepareInput("D1", "2.0")).thenReturn(publication);
        input.accept(dm()); run(); verify(bot).discardInputPublication(publication); assertThat(updatedText()).contains("회의 7");
    }
    @Test void dmClaimLoserDiscardsOnlyTeamProgressAndKeepsOriginNotice() throws Exception {
        // 파일 선점 경쟁에서 지면 팀 예약 메시지만 지우고 DM 접수 메시지를 재사용한다.
        var publication = new SlackBotService.InputPublication("D1", "2.0", "CTEAM", "3.0");
        when(bot.prepareInput("D1", "2.0")).thenReturn(publication); when(store.claim("F1")).thenReturn(false);
        input.accept(dm()); run(); verify(bot).discardInputPublication(publication); assertThat(updatedText()).contains("처리 중"); verifyNoInteractions(downloader, audio);
    }
    @Test void dmTranscriptionFailureDiscardsTeamProgressAndUpdatesOnlyOrigin() throws Exception {
        // 회의 생성 전 전사 실패는 팀 임시 메시지를 지우고 DM에만 안전한 오류를 남긴다.
        var publication = new SlackBotService.InputPublication("D1", "2.0", "CTEAM", "3.0");
        when(bot.prepareInput("D1", "2.0")).thenReturn(publication);
        when(audio.submitFromSlack(any(), any(), any())).thenThrow(new AudioMeetingException(HttpStatus.BAD_GATEWAY, "AUDIO_TRANSCRIPTION_FAILED", "private failure"));
        input.accept(dm()); run(); verify(bot).discardInputPublication(publication); assertThat(updatedText()).contains("음성");
    }
    @Test void dmHashRaceDiscardsTeamProgressOnceAndUpdatesOnlyOrigin() throws Exception {
        // 본문 저장 경쟁에서 패배한 임시 회의는 팀 예약 메시지를 한 번만 정리한다.
        var publication = new SlackBotService.InputPublication("D1", "2.0", "CTEAM", "3.0");
        when(bot.prepareInput("D1", "2.0")).thenReturn(publication);
        Meeting candidate = Meeting.create("private transcript", MeetingSource.SLACK_AUDIO);
        when(audio.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> beforeSave = call.getArgument(1); Consumer<Meeting> discard = call.getArgument(2);
            beforeSave.accept(candidate); discard.accept(candidate); return result();
        });
        input.accept(dm()); run(); verify(bot).discardInputPublication(publication); assertThat(updatedText()).contains("회의 7");
    }

}
