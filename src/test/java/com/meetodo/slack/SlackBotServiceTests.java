package com.meetodo.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingResults;
import com.meetodo.notification.NotificationDeliveryException;
import com.meetodo.notification.NotificationMessage;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.response.chat.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SlackBotServiceTests {
    final MethodsClient client = mock(MethodsClient.class);
    final SlackMessageReferenceRepository refs = mock(SlackMessageReferenceRepository.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final TaskService service = mock(TaskService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final SlackBotService bot = new SlackBotService(client, "C1", refs, tasks, service, mock(MeetingResults.class),
            mock(MeetingRepository.class), new ObjectMapper(), transactions);
    final LocalDate date = LocalDate.of(2026, 11, 12);
    final NotificationMessage message = new NotificationMessage(null, "알림", List.of(), List.of(), "기준일", NotificationMessage.Type.DAILY_DIGEST, date);
    Task task;
    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        task = Task.create("할 일 <@UNSAFE>", null, date);
        ReflectionTestUtils.setField(task, "id", 7L);
        when(refs.findByChannelIdAndMessageTs("C1", "1.2")).thenReturn(Optional.of(new SlackMessageReference("C1", "1.2", null, date, "[7]")));
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task));
        when(tasks.findDigestTasks(TaskStatus.TODO, date.plusDays(3))).thenReturn(List.of(task));
    }
    @Test void postUsesConfiguredChannelAndDisablesAutomaticParsing() throws Exception {
        // 봇 API 전송은 채널 고정, 자동 멘션 및 링크 미리보기를 끈다.
        var response = new ChatPostMessageResponse(); response.setOk(true); response.setChannel("C1"); response.setTs("1.2");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);
        bot.send(message);
        var request = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client).chatPostMessage(request.capture());
        assertThat(request.getValue().getChannel()).isEqualTo("C1");
        assertThat(request.getValue().getParse()).isEqualTo("none");
        assertThat(request.getValue().isLinkNames()).isFalse();
        assertThat(request.getValue().isUnfurlLinks()).isFalse();
        assertThat(request.getValue().isUnfurlMedia()).isFalse();
        verify(transactions).commit(any());
    }
    @Test void slackReturnedMissingScopeIsPreserved() throws Exception {
        // 실제 발견한 missing_scope를 INTERNAL_ERROR로 덮어쓰지 않는다.
        var response = new ChatPostMessageResponse(); response.setError("missing_scope");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);
        assertThatThrownBy(() -> bot.send(message)).isInstanceOfSatisfying(NotificationDeliveryException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("missing_scope"));
    }

    @Test void rejectedPostDoesNotStoreMessageReference() throws Exception {
        // 전송에 실패한 메시지 좌표는 저장하지 않는다.
        var response = new ChatPostMessageResponse(); response.setError("provider xoxb-secret");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);
        assertThatThrownBy(() -> bot.send(message)).isInstanceOf(NotificationDeliveryException.class).hasMessage("Notification delivery failed");
        verifyNoInteractions(transactions);
    }
    @Test void sdkExceptionIsSanitized() throws Exception {
        // 공급자 예외 본문과 토큰을 사용자 오류에 노출하지 않는다.
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenThrow(new java.io.IOException("xoxb-secret provider payload"));
        assertThatThrownBy(() -> bot.send(message)).hasMessage("Notification delivery failed").hasNoCause();
    }
    @Test void storedMembershipRejectsForgedTaskId() throws Exception {
        // 버튼 value만 바꾼 요청은 메시지에 기록된 업무 집합으로 거절한다.
        bot.complete("C1", "1.2", "UACTOR", "999");
        verifyNoInteractions(service);
        verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void unknownMessageCannotCompleteTask() throws Exception {
        // 서버가 보내지 않은 메시지에서는 업무를 완료하지 않는다.
        bot.complete("C1", "unknown", "UACTOR", "7");
        verifyNoInteractions(service);
        verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void malformedTaskIdIsRejected() throws Exception {
        // 숫자가 아닌 버튼 value는 저장 계층에 도달하지 않는다.
        bot.complete("C1", "1.2", "UACTOR", "7oops");
        verifyNoInteractions(service);
        verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
    }
    @Test void forbiddenCompletionOnlyRepliesEphemerally() throws Exception {
        // 담당자가 아니면 메시지 갱신 없이 짧은 비공개 안내를 보낸다.
        when(service.completeFromSlack(7L, "UOTHER")).thenThrow(new TaskCompletionForbiddenException());
        bot.complete("C1", "1.2", "UOTHER", "7");
        var request = ArgumentCaptor.forClass(ChatPostEphemeralRequest.class);
        verify(client).chatPostEphemeral(request.capture());
        assertThat(request.getValue().getText()).isEqualTo("담당자만 완료할 수 있어요");
        verify(client, never()).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void completionRebuildsFromCurrentDatabaseTask() throws Exception {
        // 완료 뒤 DB에서 읽은 상태와 actor로 원래 메시지를 갱신한다.
        when(service.completeFromSlack(7L, "UACTOR")).thenAnswer(call -> {
            task.changeStatus(TaskStatus.DONE, "UACTOR", java.time.Instant.EPOCH); return null;
        });
        var response = new ChatUpdateResponse(); response.setOk(true);
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(response);
        bot.complete("C1", "1.2", "UACTOR", "7");
        var request = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client).chatUpdate(request.capture());
        assertThat(request.getValue().getTs()).isEqualTo("1.2");
        assertThat(request.getValue().getBlocksAsString()).contains("완료 (<@UACTOR>)", "&lt;@UNSAFE&gt;").doesNotContain("meetodo_complete_task");
    }
    @Test void failedUpdateDoesNotUndoCompletedTask(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // Slack 갱신 실패 후에도 완료는 유지하며 비밀값 없이 로그만 남긴다.
        when(service.completeFromSlack(7L, "UACTOR")).thenAnswer(call -> {
            task.changeStatus(TaskStatus.DONE, "UACTOR", java.time.Instant.EPOCH); return null;
        });
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenThrow(new java.io.IOException("secret provider body"));
        bot.complete("C1", "1.2", "UACTOR", "7");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE);
        verify(client, never()).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
        assertThat(output.getAll()).contains("Slack message refresh failed: INTERNAL_ERROR").doesNotContain("secret provider body");
    }
    @Test void missingStoredTaskOnlyRepliesEphemerally() throws Exception {
        // 메시지 목록에 있더라도 DB에서 사라진 Task는 완료나 갱신을 가장하지 않는다.
        when(service.completeFromSlack(7L, "UACTOR")).thenThrow(new com.meetodo.task.exception.ResourceNotFoundException("TASK_NOT_FOUND", "missing"));
        bot.complete("C1", "1.2", "UACTOR", "7");
        verify(client).chatPostEphemeral(any(ChatPostEphemeralRequest.class));
        verify(client, never()).chatUpdate(any(ChatUpdateRequest.class));
    }

    @Test void alreadyDoneTaskRefreshRemovesButtonAndPreservesActor() throws Exception {
        // 이미 DONE인 원본 DB 상태로 갱신하며 완료자나 시각을 메시지에서 추측하지 않는다.
        task.changeStatus(TaskStatus.DONE, "UFIRST", java.time.Instant.EPOCH);
        var response = new ChatUpdateResponse(); response.setOk(true);
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(response);
        bot.complete("C1", "1.2", "UACTOR", "7");
        var request = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client).chatUpdate(request.capture());
        assertThat(request.getValue().getBlocksAsString()).contains("완료 (<@UFIRST>)").doesNotContain("meetodo_complete_task");
        assertThat(task.getCompletedBySlackUserId()).isEqualTo("UFIRST");
    }

    @Test void inputProgressPostsBeforeMeetingPersistence() throws Exception {
        // Meeting 저장 전 게시만 수행하고 반환 좌표를 미저장 객체에 붙인다.
        var meeting = com.meetodo.meeting.Meeting.create("입력",com.meetodo.meeting.MeetingSource.SLACK_TEXT);
        var repository = mock(MeetingRepository.class);
        var inputBot = new SlackBotService(client,"C1",refs,tasks,service,mock(MeetingResults.class),repository,new ObjectMapper(),transactions);
        var response=new ChatPostMessageResponse(); response.setOk(true); response.setChannel("C2"); response.setTs("2.3");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);
        inputBot.startInput(meeting,"C2");
        var request=ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client).chatPostMessage(request.capture());
        assertThat(request.getValue().getChannel()).isEqualTo("C2");
        assertThat(request.getValue().getText()).isEqualTo("⏳ 회의록 정리 중...");
        assertThat(meeting.getSlackChannelId()).isEqualTo("C2"); assertThat(meeting.getSlackMessageTs()).isEqualTo("2.3");
        assertThat(meeting.getId()).isNull();
        verifyNoInteractions(repository, refs, transactions);
    }

    @Test void discardedInputDeletesItsOwnTemporaryMessageWithoutDatabaseWrites() throws Exception {
        // DB 해시 경쟁에서 진 요청은 자기 후보 객체의 임시 메시지만 정리한다.
        var candidate=com.meetodo.meeting.Meeting.create("동시 입력",com.meetodo.meeting.MeetingSource.SLACK_TEXT);
        candidate.recordSlackMessage("C2","2.3");
        var response=new ChatDeleteResponse(); response.setOk(true);
        when(client.chatDelete(any(ChatDeleteRequest.class))).thenReturn(response);
        bot.discardInputProgress(candidate);
        var request=ArgumentCaptor.forClass(ChatDeleteRequest.class); verify(client).chatDelete(request.capture());
        assertThat(request.getValue().getChannel()).isEqualTo("C2"); assertThat(request.getValue().getTs()).isEqualTo("2.3");
        verifyNoInteractions(refs,transactions);
    }

    @Test void temporaryMessageDeleteFailureIsSafeAndDoesNotEscape(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // 임시 메시지 정리 실패는 재처리를 유발하지 않으며 공급자 예외·토큰을 남기지 않는다.
        var candidate=com.meetodo.meeting.Meeting.create("원문전체삭제실패",com.meetodo.meeting.MeetingSource.SLACK_TEXT);
        candidate.recordSlackMessage("C2","2.3");
        when(client.chatDelete(any(ChatDeleteRequest.class))).thenThrow(new java.io.IOException("xoxb-secret https://hooks.slack.com/secret 원문전체삭제실패"));
        assertThatCode(() -> bot.discardInputProgress(candidate)).doesNotThrowAnyException();
        assertThat(output).contains("Slack temporary message cleanup failed: INTERNAL_ERROR")
                .doesNotContain("xoxb-secret","hooks.slack.com","원문전체삭제실패");
        verify(client).chatDelete(any(ChatDeleteRequest.class)); verifyNoInteractions(refs,transactions);
    }

}
