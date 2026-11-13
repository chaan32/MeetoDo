package com.meetodo.slack;

import com.meetodo.meeting.*;
import com.meetodo.meeting.dto.*;
import com.meetodo.notification.NotificationDeliveryException;
import com.meetodo.notification.NotificationStatus;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.ViewSubmissionContext;
import com.slack.api.bolt.handler.builtin.ViewSubmissionHandler;
import com.slack.api.bolt.request.builtin.ViewSubmissionRequest;
import com.slack.api.app_backend.views.payload.ViewSubmissionPayload;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.model.view.View;
import com.slack.api.model.view.ViewState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SlackMeetingInputTests {
    final MethodsClient client = mock(MethodsClient.class);
    final MeetingApplicationService meetings = mock(MeetingApplicationService.class);
    final SlackBotService bot = mock(SlackBotService.class);
    final Clock clock = mock(Clock.class);
    final List<Runnable> queue = new ArrayList<>();
    final List<String> replies = new ArrayList<>();
    SlackMeetingInputService input;
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-12T00:00:00Z"));
        input = new SlackMeetingInputService(client, meetings, bot, queue::add, clock);
        var response = new ViewsOpenResponse(); response.setOk(true); response.setView(View.builder().id("V1").build());
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenReturn(response);
    }
    private void open() { input.open("trigger-1", "C1", "U1", replies::add); }
    private MeetingResponse result() {
        return new MeetingResponse(7L, null, MeetingStatus.DONE, "", List.of(), 0, 100L, 1, 1, 0,
                NotificationStatus.SENT, "slack-bot", null, null);
    }
    @Test void slashCommandOpensMultilineModalWithOriginChannel() throws Exception {
        // 명령 실행 채널을 보존한 3000자 여러 줄 모달을 Slack API로 연다.
        open();
        var request = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getTriggerId()).isEqualTo("trigger-1");
        assertThat(request.getValue().getViewAsString()).contains("\"private_metadata\":\"C1\"", "\"multiline\":true", "\"max_length\":3000");
    }
    @Test void acceptedSubmissionQueuesBeforeMeetingProcessing() throws Exception {
        // 모달 제출 경로에서는 작업을 큐에 넣고 LLM 흐름을 즉시 실행하지 않는다.
        open(); input.submit("V1", "C1", "U1", "새 회의록");
        assertThat(queue).hasSize(1); verifyNoInteractions(meetings, bot);
    }
    @Test void newlyClaimedMeetingPostsProgressInOriginChannel() throws Exception {
        // 새 회의만 처리 전 훅에서 원래 명령 채널에 처리 중 메시지를 게시한다.
        open(); Meeting meeting = Meeting.create("새 회의록", MeetingSource.SLACK_TEXT);
        when(meetings.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> created = call.getArgument(1); created.accept(meeting); return result();
        });
        input.submit("V1", "C1", "U1", "새 회의록"); queue.getFirst().run();
        verify(bot).startInput(meeting, "C1"); assertThat(replies).isEmpty();
        var request = ArgumentCaptor.forClass(MeetingRequest.class); verify(meetings).submitFromSlack(request.capture(), any(), any());
        assertThat(request.getValue().rawText()).isEqualTo("새 회의록");
    }
    @Test void duplicateMeetingOnlyRepliesPrivatelyWithoutProgress() throws Exception {
        // 해시 중복은 새 처리 훅 없이 기존 회의 ID만 요청자에게 알려준다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenReturn(result());
        input.submit("V1", "C1", "U1", "같은 회의록"); queue.getFirst().run();
        assertThat(replies).containsExactly("이미 정리된 회의록입니다 (회의 7)"); verifyNoInteractions(bot);
    }
    @Test void processingFailureUsesSharedNotifierInsteadOfPostingSecondMessage() throws Exception {
        // 회의 실패의 원본 메시지 수정은 공통 알림 흐름에 맡겨 별도 채널 알림을 만들지 않는다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenThrow(new MeetingProcessingException(7L));
        input.submit("V1", "C1", "U1", "실패 회의록"); queue.getFirst().run();
        assertThat(replies).isEmpty(); verify(client, never()).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void missingBotMembershipUsesSlashResponderForInviteGuidance() throws Exception {
        // 봇이 채널에 없어 Web API 게시가 실패해도 slash 응답 경로로 초대 안내를 보낸다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenThrow(new NotificationDeliveryException("not_in_channel"));
        input.submit("V1", "C1", "U1", "새 회의록"); queue.getFirst().run();
        assertThat(replies).containsExactly("/invite @MeetoDo 후 다시 시도해 주세요.");
        verify(client, never()).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void unavailableChannelAlsoGuidesBotInvitation() {
        // 접근할 수 없는 채널 오류에도 원문·내부 오류 대신 초대 방법만 안내한다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenThrow(new NotificationDeliveryException("channel_not_found"));
        input.submit("V1", "C1", "U1", "새 회의록"); queue.getFirst().run();
        assertThat(replies).containsExactly("/invite @MeetoDo 후 다시 시도해 주세요.");
    }
    @Test void blankTranscriptNeverReachesMeetingService() {
        // Slack의 min_length를 통과한 공백만 있는 입력도 서버에서 차단한다.
        open(); input.submit("V1", "C1", "U1", "  \n ");
        assertThat(queue).isEmpty(); assertThat(replies).singleElement().asString().contains("3,000자"); verifyNoInteractions(meetings);
    }
    @Test void missingTranscriptNeverReachesMeetingService() {
        // 제출 상태에 입력 필드가 빠지더라도 빈 회의를 저장하지 않는다.
        open(); input.submit("V1", "C1", "U1", null);
        assertThat(queue).isEmpty(); verifyNoInteractions(meetings);
    }
    @Test void overlongTranscriptNeverReachesMeetingService() {
        // 조작한 제출 payload가 Slack 모달 상한을 넘으면 서버도 거절한다.
        open(); input.submit("V1", "C1", "U1", "가".repeat(3001));
        assertThat(queue).isEmpty(); assertThat(replies).singleElement().asString().contains("3,000자"); verifyNoInteractions(meetings);
    }
    @Test void exactModalLimitIsAccepted() {
        // 3000자 경계값은 잘라 저장하지 않고 그대로 큐에 넣는다.
        open(); input.submit("V1", "C1", "U1", "가".repeat(3000)); assertThat(queue).hasSize(1);
    }
    @Test void forgedOriginChannelCannotQueueMeeting() throws Exception {
        // private_metadata 변경으로 서버에 보관한 원래 채널을 바꿀 수 없다.
        open(); input.submit("V1", "COTHER", "U1", "새 회의록");
        assertThat(queue).isEmpty(); verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void anotherUserCannotSubmitOwnersModal() {
        // 다른 Slack 사용자는 기존 모달의 입력을 대신 제출할 수 없다.
        open(); input.submit("V1", "C1", "UOTHER", "새 회의록"); assertThat(queue).isEmpty();
    }
    @Test void resubmittedViewCannotQueueTwice() {
        // 한 모달 ID의 재전송은 이미 소비한 입력을 다시 처리하지 않는다.
        open(); input.submit("V1", "C1", "U1", "새 회의록"); input.submit("V1", "C1", "U1", "새 회의록");
        assertThat(queue).hasSize(1);
    }
    @Test void expiredModalRequiresFreshCommand() throws Exception {
        // slash response URL 수명인 30분을 넘긴 모달은 새 명령 입력을 요구한다.
        open(); when(clock.instant()).thenReturn(Instant.parse("2026-11-12T00:30:00Z"));
        input.submit("V1", "C1", "U1", "새 회의록");
        assertThat(queue).isEmpty(); var response = ArgumentCaptor.forClass(ChatPostEphemeralRequest.class);
        verify(client).chatPostEphemeral(response.capture()); assertThat(response.getValue().getText()).contains("만료");
    }
    @Test void closingModalRemovesPendingSubmission() {
        // 취소한 모달은 서버 메모리의 응답 경로도 제거한다.
        open(); input.close("V1", "U1"); input.submit("V1", "C1", "U1", "새 회의록"); assertThat(queue).isEmpty();
    }
    @Test void rejectedProcessingQueueRepliesPrivately() {
        // 비동기 큐가 가득 차도 새 회의를 저장하거나 요청자에게 내부 예외를 노출하지 않는다.
        input = new SlackMeetingInputService(client, meetings, bot, job -> { throw new RejectedExecutionException(String.join("", "xox", "b-secret")); }, clock);
        open(); input.submit("V1", "C1", "U1", "새 회의록");
        assertThat(replies).singleElement().asString().contains("현재 요청이 많아요").doesNotContain(String.join("", "xox", "b-secret")); verifyNoInteractions(meetings);
    }
    @Test void unexpectedProviderFailureDoesNotLeakSecretsOrTranscript(CapturedOutput output) {
        // 공급자 예외와 회의록 원문은 사용자 응답이나 로그에 남지 않는다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenThrow(new IllegalStateException(String.join("", "xox", "b-secret https", "://hooks.slack.com/secret 회의록원문전체")));
        input.submit("V1", "C1", "U1", "회의록원문전체"); queue.getFirst().run();
        assertThat(String.join(" ", replies)).doesNotContain(String.join("", "xox", "b-secret"), "hooks.slack.com", "회의록원문전체");
        assertThat(output).doesNotContain(String.join("", "xox", "b-secret"), "hooks.slack.com", "회의록원문전체");
    }
    @Test void modalOpenFailureUsesSafePrivateResponse(CapturedOutput output) throws Exception {
        // 모달 API 실패의 토큰·본문을 보여주지 않고 요청자에게 다시 실행하도록 안내한다.
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenThrow(new java.io.IOException(String.join("", "xap", "p-secret provider body")));
        open(); assertThat(replies).singleElement().asString().contains("입력 창을 열지 못했어요").doesNotContain(String.join("", "xap", "p-secret"));
        assertThat(output).doesNotContain(String.join("", "xap", "p-secret"), "provider body");
    }
    @Test void invalidOriginCannotOpenModal() throws Exception {
        // 실제 Slack 채널 식별자 형식이 아닌 값은 메타데이터로 전달하지 않는다.
        input.open("trigger-1", String.join("", "https", "://hooks.slack.com/secret"), "U1", replies::add);
        verify(client, never()).viewsOpen(any(ViewsOpenRequest.class)); assertThat(replies).hasSize(1);
    }
    @Test void pendingModalsAreBoundedWithoutEvictingExistingOwner() throws Exception {
        // 보관 상한에 도달하면 새 모달을 거절하고 기존 응답 경로를 임의로 지우지 않는다.
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenAnswer(call -> {
            var response = new ViewsOpenResponse(); response.setOk(true); response.setView(View.builder().id("V" + sequence.incrementAndGet()).build()); return response;
        });
        for (int count = 0; count < 1001; count++) open();
        verify(client, times(1000)).viewsOpen(any(ViewsOpenRequest.class));
        assertThat(replies).singleElement().asString().contains("현재 요청이 많아요");
        input.submit("V1", "C1", "U1", "새 회의록"); assertThat(queue).hasSize(1);
    }
    @Test void expiredPendingEntriesFreeCapacityForNewModal() throws Exception {
        // 오래된 모달이 남아 있어도 만료 후 새 명령이 서버 메모리를 다시 사용할 수 있다.
        open(); when(clock.instant()).thenReturn(Instant.parse("2026-11-12T00:30:00Z")); open();
        input.submit("V1", "C1", "U1", "새 회의록"); assertThat(queue).hasSize(1);
    }
    @Test void unexpectedNotificationFailureDoesNotExposeProviderCode(CapturedOutput output) {
        // 초대 안내 대상이 아닌 전송 오류는 내부 코드 없이 시작 실패만 알린다.
        open(); when(meetings.submitFromSlack(any(), any(), any())).thenThrow(new NotificationDeliveryException(String.join("", "invalid_auth xox", "b-secret")));
        input.submit("V1", "C1", "U1", "새 회의록"); queue.getFirst().run();
        assertThat(replies).singleElement().asString().contains("시작하지 못했어요").doesNotContain("invalid_auth", String.join("", "xox", "b-secret"));
        assertThat(output).contains("code=INTERNAL_ERROR").doesNotContain(String.join("", "xox", "b-secret"));
    }
    @Test void submissionHandlerAcknowledgesBeforeQueuingMeeting() throws Exception {
        // 실제 등록한 Bolt 제출 핸들러는 ack를 먼저 호출한 뒤 회의 처리를 큐에 넣는다.
        List<String> order = new ArrayList<>();
        input = new SlackMeetingInputService(client, meetings, bot, job -> { order.add("queued"); queue.add(job); }, clock);
        open(); App app = mock(App.class); input.register(app);
        var handler = ArgumentCaptor.forClass(ViewSubmissionHandler.class); verify(app).viewSubmission(eq(SlackMeetingInputService.CALLBACK), handler.capture());
        var request = mock(ViewSubmissionRequest.class); var context = mock(ViewSubmissionContext.class);
        var payload = new ViewSubmissionPayload(); var user = new ViewSubmissionPayload.User(); user.setId("U1"); payload.setUser(user);
        ViewState.Value value = new ViewState.Value(); value.setValue("새 회의록");
        payload.setView(View.builder().id("V1").privateMetadata("C1").state(ViewState.builder().values(Map.of(SlackMeetingInputService.BLOCK,
                Map.of(SlackMeetingInputService.ACTION,value))).build()).build());
        when(request.getPayload()).thenReturn(payload); doAnswer(call -> { order.add("ack"); return null; }).when(context).ack();
        handler.getValue().apply(request,context);
        assertThat(order).containsExactly("ack","queued"); verifyNoInteractions(meetings);
    }
    @Test void concurrentHashLoserCleansProgressAndRepliesAsDuplicate() {
        // 게시 이후 DB 경쟁에서 지면 임시 메시지를 정리하고 중복 안내만 요청자에게 보낸다.
        open(); Meeting candidate=Meeting.create("동시 회의",MeetingSource.SLACK_TEXT);
        when(meetings.submitFromSlack(any(),any(),any())).thenAnswer(call -> {
            Consumer<Meeting> post=call.getArgument(1); Consumer<Meeting> discard=call.getArgument(2);
            post.accept(candidate); discard.accept(candidate); return result();
        });
        input.submit("V1","C1","U1","동시 회의"); queue.getFirst().run();
        verify(bot).startInput(candidate,"C1"); verify(bot).discardInputProgress(candidate);
        assertThat(replies).containsExactly("이미 정리된 회의록입니다 (회의 7)");
    }

    @Test void dmModalStartsSharedPublicationFromOriginalDm() {
        // DM에서 연 모달도 원래 DM 채널을 공유 게시 서비스에 넘긴다.
        input.open("trigger-1", "D1", "U1", replies::add);
        Meeting meeting = Meeting.create("DM 회의록", MeetingSource.SLACK_TEXT);
        when(meetings.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> hook = call.getArgument(1); hook.accept(meeting); return result();
        });
        input.submit("V1", "D1", "U1", "DM 회의록"); queue.getFirst().run();
        verify(bot).startInput(meeting, "D1"); assertThat(replies).isEmpty();
    }
    @Test void dmHashRaceDuplicateUpdatesOriginalReceiptInsteadOfSecondReply() {
        // DM 모달의 저장 경쟁 패배는 팀 임시 결과를 지우고 기존 DM 접수 메시지에 중복을 알린다.
        input.open("trigger-1", "D1", "U1", replies::add);
        Meeting candidate = Meeting.create("동시 DM 회의", MeetingSource.SLACK_TEXT);
        doAnswer(call -> { candidate.recordSlackInput("D1", "2.0"); candidate.recordSlackMessage("CTEAM", "3.0"); return null; }).when(bot).startInput(candidate, "D1");
        when(meetings.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> post = call.getArgument(1); Consumer<Meeting> discard = call.getArgument(2);
            post.accept(candidate); discard.accept(candidate); return result();
        });
        input.submit("V1", "D1", "U1", "동시 DM 회의"); queue.getFirst().run();
        verify(bot).discardInputProgress(candidate); verify(bot).showInputNotice("D1", "2.0", "이미 정리된 회의록입니다 (회의 7)"); assertThat(replies).isEmpty();
    }
    @Test void dmPersistenceFailureCleansTeamProgressAndUpdatesOriginalReceipt() {
        // 알림 좌표를 만든 뒤 저장이 실패하면 팀 임시 결과를 지우고 원래 DM만 실패로 바꾼다.
        input.open("trigger-1", "D1", "U1", replies::add);
        Meeting candidate = Meeting.create("저장 실패 DM 회의", MeetingSource.SLACK_TEXT);
        doAnswer(call -> { candidate.recordSlackInput("D1", "2.0"); candidate.recordSlackMessage("CTEAM", "3.0"); return null; }).when(bot).startInput(candidate, "D1");
        when(meetings.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> post = call.getArgument(1); post.accept(candidate); throw new IllegalStateException("private persistence error");
        });
        input.submit("V1", "D1", "U1", "저장 실패 DM 회의"); queue.getFirst().run();
        verify(bot).discardInputProgress(candidate); verify(bot).showInputNotice(eq("D1"), eq("2.0"), contains("사용할 수 없어요")); assertThat(replies).isEmpty();
    }
    @Test void dmTeamInvitationFailureUsesOriginalReceiptAndCreatesNoMeetingResult() {
        // 팀 채널 게시가 실패해도 이미 생성한 DM 접수 메시지에서만 초대를 안내한다.
        input.open("trigger-1", "D1", "U1", replies::add);
        Meeting candidate = Meeting.create("DM 회의", MeetingSource.SLACK_TEXT);
        doAnswer(call -> { candidate.recordSlackInput("D1", "2.0"); throw new NotificationDeliveryException("not_in_channel"); }).when(bot).startInput(candidate, "D1");
        when(meetings.submitFromSlack(any(), any(), any())).thenAnswer(call -> {
            Consumer<Meeting> post = call.getArgument(1); post.accept(candidate); return result();
        });
        input.submit("V1", "D1", "U1", "DM 회의"); queue.getFirst().run();
        verify(bot).showInputNotice(eq("D1"), eq("2.0"), contains("/invite @MeetoDo")); assertThat(replies).isEmpty();
    }

}
