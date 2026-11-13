package com.meetodo.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.meeting.dto.*;
import com.meetodo.member.Member;
import com.meetodo.notification.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.methods.response.chat.*;
import com.slack.api.methods.response.conversations.ConversationsOpenResponse;
import com.slack.api.model.Conversation;
import java.time.*;
import java.util.*;
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
class SlackDirectMessageTests {
    private final MethodsClient client = mock(MethodsClient.class);
    private final SlackMessageReferenceRepository refs = mock(SlackMessageReferenceRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskService taskService = mock(TaskService.class);
    private final MeetingResults results = mock(MeetingResults.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SlackBotService bot = new SlackBotService(client, "C1", refs, tasks, taskService, results,
            meetings, new ObjectMapper(), transactions);
    private final List<SlackMessageReference> stored = new ArrayList<>();
    private final List<Task> current = new ArrayList<>();
    private final Map<Long,String> types = new HashMap<>();
    private final NotificationMessage message = new NotificationMessage(1L, "회의", List.of(), List.of(), "회의 1");
    private final Meeting meeting = Meeting.create("회의", MeetingSource.API_TEXT);
    private int dmSequence;

    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(meeting, "id", 1L);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(meetings.findById(1L)).thenReturn(Optional.of(meeting));
        when(results.get(1L)).thenAnswer(call -> new MeetingResponse(1L,null,MeetingStatus.DONE,"",current.stream()
                .map(task -> MeetingTaskResponse.from(task,types.get(task.getId()),1,null,null)).toList(),0,100L,1,1,0,
                NotificationStatus.PENDING,"slack-bot",null,null));
        when(tasks.findAllWithAssigneeByIdIn(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0); return current.stream().filter(task -> ids.contains(task.getId())).toList();
        });
        when(refs.save(any())).thenAnswer(call -> { SlackMessageReference ref=call.getArgument(0); stored.add(ref); return ref; });
        when(refs.findAll()).thenAnswer(call -> new ArrayList<>(stored));
        when(refs.findByChannelIdAndMessageTs(anyString(),anyString())).thenAnswer(call -> stored.stream()
                .filter(ref -> Objects.equals(ref.getChannelId(),call.getArgument(0)) && Objects.equals(ref.getMessageTs(),call.getArgument(1))).findFirst());
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(call -> {
            ChatPostMessageRequest request=call.getArgument(0);
            var response=new ChatPostMessageResponse(); response.setOk(true); response.setChannel(request.getChannel());
            response.setTs(request.getChannel().equals("C1") ? "1.2" : "2." + ++dmSequence); return response;
        });
        var permalink=new ChatGetPermalinkResponse(); permalink.setOk(true); permalink.setPermalink("https://meetodo.slack.com/archives/C1/p12");
        when(client.chatGetPermalink(any(ChatGetPermalinkRequest.class))).thenReturn(permalink);
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenAnswer(call -> {
            ConversationsOpenRequest request=call.getArgument(0); Conversation conversation=new Conversation();
            conversation.setId("D" + request.getUsers().getFirst());
            var response=new ConversationsOpenResponse(); response.setOk(true); response.setChannel(conversation); return response;
        });
        var update=new ChatUpdateResponse(); update.setOk(true); when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(update);
    }
    private Task add(long id,String slackId,String resultType) {
        Member member=slackId==null ? null : new Member("전","해찬","백엔드팀",slackId);
        Task task=Task.create("할 일 " + id,member,LocalDate.of(2026,11,20)); ReflectionTestUtils.setField(task,"id",id);
        current.add(task); types.put(id,resultType); return task;
    }
    private void enable() { ReflectionTestUtils.setField(bot,"dmEnabled",true); }

    @Test void createdTasksAreGroupedIntoOneDmPerRealAssignee() throws Exception {
        // 같은 사람의 새 업무는 완료 버튼과 채널 원문 링크를 갖춘 DM 한 통에 묶는다.
        enable(); add(7,"UHAECHAN","CREATED"); add(8,"UHAECHAN","CREATED"); add(9,"UOTHER","CREATED");
        bot.send(message);
        var requests=ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client,times(3)).chatPostMessage(requests.capture());
        var dm=requests.getAllValues().stream().filter(request -> !request.getChannel().equals("C1")).toList();
        assertThat(dm).hasSize(2); assertThat(dm.getFirst().getText()).contains("📝 새 할 일이 배정됐어요","할 일 7","할 일 8","https://meetodo.slack.com/archives/C1/p12");
        assertThat(dm.getFirst().getBlocksAsString()).contains("meetodo_complete_task","\"value\":\"7\"","\"value\":\"8\"");
        assertThat(stored).hasSize(3);
    }
    @Test void existingTaskDoesNotSendDm() throws Exception {
        // 재언급한 기존 업무는 채널에만 알리고 DM을 다시 보내지 않는다.
        enable(); add(7,"UHAECHAN","EXISTING"); bot.send(message);
        verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class)); verify(client,never()).conversationsOpen(any(ConversationsOpenRequest.class));
    }
    @Test void unassignedTaskDoesNotSendDm() throws Exception {
        // 담당자가 미정인 업무에는 DM 수신자를 추측하지 않는다.
        enable(); add(7,null,"CREATED"); bot.send(message);
        verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
    }
    @Test void fakeSlackIdDoesNotSendDm() throws Exception {
        // 개발용 U-DEV- 식별자에는 DM을 시도하지 않는다.
        enable(); add(7,"U-DEV-HAECHAN","CREATED"); bot.send(message);
        verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
    }
    @Test void disabledSettingDoesNotSendDm() throws Exception {
        // 명시적으로 켜지 않은 기본 실행은 실제 담당자가 있어도 채널 알림만 보낸다.
        add(7,"UHAECHAN","CREATED"); bot.send(message);
        verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class)); verify(client,never()).chatGetPermalink(any(ChatGetPermalinkRequest.class));
    }
    @Test void completionFromDmRefreshesChannelAndDm() throws Exception {
        // DM에서 완료해도 채널과 관련 DM을 모두 현재 DB 상태로 다시 그린다.
        enable(); Task task=add(7,"UHAECHAN","CREATED"); bot.send(message);
        when(taskService.completeFromSlack(7L,"UHAECHAN")).thenAnswer(call -> { task.changeStatus(TaskStatus.DONE,"UHAECHAN",Instant.EPOCH); return null; });
        bot.complete("DUHAECHAN","2.1","UHAECHAN","7");
        var updates=ArgumentCaptor.forClass(ChatUpdateRequest.class); verify(client,times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).extracting(ChatUpdateRequest::getChannel).containsExactlyInAnyOrder("C1","DUHAECHAN");
        assertThat(updates.getAllValues()).allSatisfy(update -> assertThat(update.getBlocksAsString()).contains("완료 (<@UHAECHAN>)").doesNotContain("meetodo_complete_task"));
    }
    @Test void completionFromChannelRefreshesDm() throws Exception {
        // 채널에서 완료할 때에도 같은 업무가 포함된 DM의 버튼을 제거한다.
        enable(); Task task=add(7,"UHAECHAN","CREATED"); bot.send(message);
        when(taskService.completeFromSlack(7L,"UHAECHAN")).thenAnswer(call -> { task.changeStatus(TaskStatus.DONE,"UHAECHAN",Instant.EPOCH); return null; });
        bot.complete("C1","1.2","UHAECHAN","7");
        var updates=ArgumentCaptor.forClass(ChatUpdateRequest.class); verify(client,times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).extracting(ChatUpdateRequest::getChannel).contains("DUHAECHAN");
    }
    @Test void dmFailureDoesNotFailChannelAndRecordsSlackError() throws Exception {
        // DM 권한 오류는 채널 성공을 유지하고 별도 수신자 실패 기록에 남긴다.
        enable(); add(7,"UHAECHAN","CREATED"); var failure=new ConversationsOpenResponse(); failure.setError("missing_scope");
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenReturn(failure);
        assertThatCode(() -> bot.send(message)).doesNotThrowAnyException();
        assertThat(stored).hasSize(2); var dm=stored.getLast();
        assertThat(dm.getDeliveryStatus().name()).isEqualTo("FAILED"); assertThat(dm.getErrorCode()).isEqualTo("missing_scope");
        assertThat(meeting.getSlackChannelId()).isEqualTo("C1");
    }
    @Test void failedRefreshDoesNotStopOtherMessageUpdates() throws Exception {
        // 한 좌표의 갱신 실패가 다른 좌표 갱신이나 이미 저장된 완료를 중단하지 않는다.
        enable(); Task task=add(7,"UHAECHAN","CREATED"); bot.send(message);
        when(taskService.completeFromSlack(7L,"UHAECHAN")).thenAnswer(call -> { task.changeStatus(TaskStatus.DONE,"UHAECHAN",Instant.EPOCH); return null; });
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenThrow(new java.io.IOException(String.join("", "xox", "b-secret")));
        bot.complete("C1","1.2","UHAECHAN","7");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE); verify(client,times(2)).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void malformedPermalinkDoesNotReachDmPayload() throws Exception {
        // 공급자가 잘못 반환한 링크를 Block Kit 마크다운에 그대로 삽입하지 않는다.
        enable(); add(7,"UHAECHAN","CREATED"); var response=new ChatGetPermalinkResponse(); response.setOk(true); response.setPermalink("https://evil.example/<@UATTACK>");
        when(client.chatGetPermalink(any(ChatGetPermalinkRequest.class))).thenReturn(response);
        bot.send(message); verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
        assertThat(stored.getLast().getDeliveryStatus().name()).isEqualTo("FAILED");
    }
    @Test void failedDmReferenceCannotAuthorizeCompletion() throws Exception {
        // 전송 실패 행은 좌표를 가진 경우에도 유효한 완료 버튼의 근거가 될 수 없다.
        add(7,"UHAECHAN","CREATED");
        stored.add(SlackMessageReference.dm("D1","2.1",meeting,"[7]","UHAECHAN",SlackMessageReference.DeliveryStatus.FAILED,"missing_scope",null));
        bot.complete("D1","2.1","UHAECHAN","7");
        verifyNoInteractions(taskService); verify(client,never()).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void dmSdkExceptionDoesNotLeakTokenOrAffectChannel(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // SDK 예외의 토큰과 본문 대신 안전한 코드만 기록하고 채널 성공을 유지한다.
        enable(); add(7,"UHAECHAN","CREATED");
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenThrow(new java.io.IOException(String.join("", "xox", "b-secret xap", "p-secret provider body")));
        assertThatCode(() -> bot.send(message)).doesNotThrowAnyException();
        assertThat(stored.getLast().getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(output.getAll()).contains("Slack DM delivery failed: INTERNAL_ERROR").doesNotContain(String.join("", "xox", "b-secret"),String.join("", "xap", "p-secret"),"provider body");
        assertThat(meeting.getSlackChannelId()).isEqualTo("C1");
    }
    @Test void oneDmPostFailureDoesNotSkipOtherRecipients() throws Exception {
        // 한 담당자의 DM 전송 실패를 기록하고 다음 담당자에게 계속 전송한다.
        enable(); add(7,"UHAECHAN","CREATED"); add(8,"UOTHER","CREATED");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(call -> {
            ChatPostMessageRequest request=call.getArgument(0); var response=new ChatPostMessageResponse();
            if (request.getChannel().equals("DUHAECHAN")) { response.setError("channel_not_found"); return response; }
            response.setOk(true); response.setChannel(request.getChannel()); response.setTs(request.getChannel().equals("C1") ? "1.2" : "3.1"); return response;
        });
        bot.send(message);
        assertThat(stored).hasSize(3);
        assertThat(stored.get(1).getErrorCode()).isEqualTo("channel_not_found");
        assertThat(stored.get(2).getDeliveryStatus()).isEqualTo(SlackMessageReference.DeliveryStatus.SENT);
    }
    @Test void permalinkFailureIsRecordedWithoutFailingChannel() throws Exception {
        // 채널 원문 링크를 얻지 못하면 불완전한 DM 대신 수신자별 실패 기록을 남긴다.
        enable(); add(7,"UHAECHAN","CREATED");
        var response=new ChatGetPermalinkResponse(); response.setError("channel_not_found");
        when(client.chatGetPermalink(any(ChatGetPermalinkRequest.class))).thenReturn(response);
        bot.send(message);
        assertThat(stored.getLast().getErrorCode()).isEqualTo("channel_not_found");
        verify(client,never()).conversationsOpen(any(ConversationsOpenRequest.class));
        verify(client,times(1)).chatPostMessage(any(ChatPostMessageRequest.class));
    }
    @Test void assignmentSendsDmForExistingTaskAndKeepsActor() throws Exception {
        // 사후 배정은 CREATED 여부와 관계없이 지정자와 채널 링크가 있는 DM을 한 번 보낸다.
        enable(); add(7, "UHAECHAN", "EXISTING"); bot.send(message);
        bot.assigned(7L, "UACTOR");
        var requests = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client, times(2)).chatPostMessage(requests.capture());
        assertThat(requests.getAllValues().getLast().getText()).contains("📝 새 할 일이 배정됐어요 (지정: <@UACTOR>)", "할 일 7");
        assertThat(stored.getLast().getMessageType()).isEqualTo(SlackMessageReference.MessageType.DM);
        assertThat(stored.getLast().getPermalink()).isEqualTo("https://meetodo.slack.com/archives/C1/p12");
        verify(client).chatUpdate(any(ChatUpdateRequest.class));
    }
    @Test void disabledAssignmentDmStillRefreshesChannel() throws Exception {
        // DM 설정을 끈 실행에서도 배정된 업무의 기존 메시지를 현재 상태로 갱신한다.
        add(7, "UHAECHAN", "EXISTING"); bot.send(message); bot.assigned(7L, "UACTOR");
        verify(client).chatUpdate(any(ChatUpdateRequest.class));
        verify(client, never()).conversationsOpen(any(ConversationsOpenRequest.class));
    }

    @Test void directApiAssignmentWithoutMeetingSendsDmAndStoresReference() throws Exception {
        // 회의나 채널 메시지가 없는 직접 API 업무도 실제 담당자에게 배정 DM을 전송한다.
        enable(); add(7, "UHAECHAN", "EXISTING"); bot.assigned(7L, "UACTOR");
        var request = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client).chatPostMessage(request.capture());
        assertThat(request.getValue().getText()).contains("지정: <@UACTOR>", "할 일 7").doesNotContain("원래 채널 메시지");
        assertThat(stored).hasSize(1); assertThat(stored.getFirst().getMeetingId()).isNull();
        assertThat(stored.getFirst().getTaskIds()).isEqualTo("[7]");
        verify(client, never()).chatGetPermalink(any(ChatGetPermalinkRequest.class));
    }
    @Test void completionRefreshesAssignmentDmWithoutMeeting() throws Exception {
        // 직접 배정 DM에서도 완료 시 회의 조회나 날짜 알림으로 바꾸지 않고 현재 업무를 표시한다.
        enable(); Task task = add(7, "UHAECHAN", "EXISTING"); bot.assigned(7L, "UACTOR");
        when(taskService.completeFromSlack(7L, "UHAECHAN")).thenAnswer(call -> { task.changeStatus(TaskStatus.DONE, "UHAECHAN", Instant.EPOCH); return null; });
        bot.complete("DUHAECHAN", "2.1", "UHAECHAN", "7");
        var request = ArgumentCaptor.forClass(ChatUpdateRequest.class); verify(client).chatUpdate(request.capture());
        assertThat(request.getValue().getText()).contains("완료 (<@UHAECHAN>)").doesNotContain("meetodo_assign_");
    }

}
