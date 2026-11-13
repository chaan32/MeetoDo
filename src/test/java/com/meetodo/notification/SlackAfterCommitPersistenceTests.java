package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.*;
import com.meetodo.slack.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "slack-after-commit-persistence-test"})
@Import(SlackAfterCommitPersistenceTests.BotTestConfiguration.class)
class SlackAfterCommitPersistenceTests {
    @Autowired MockMvc mvc;
    @Autowired SlackMessageReferenceRepository references;
    @Autowired MeetingRepository meetings;
    @Autowired MeetingApplicationService application;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired SlackBotService bot;
    @Autowired com.meetodo.member.MemberRepository members;
    @MockitoBean MethodsClient client;
    @MockitoBean MeetingAgent agent;

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("slack-after-commit-persistence-test")
    static class BotTestConfiguration {
        @Bean SlackBotService testBot(MethodsClient client, SlackMessageReferenceRepository refs, TaskRepository tasks,
                                      TaskService service, MeetingResults results, MeetingRepository meetings,
                                      ObjectMapper mapper, PlatformTransactionManager manager) {
            return new SlackBotService(client, "CTEST", refs, tasks, service, results, meetings, mapper, manager);
        }
        @Bean Notifier testNotifier(SlackBotService service) {
            return new Notifier() {
                public String type() { return "slack-bot"; }
                public void send(NotificationMessage message) { service.send(message); }
            };
        }
    }

    @AfterEach void clearMessageReferences() { references.deleteAll(); bot.setDmEnabled(false); }

    @BeforeEach void prepare() throws Exception {
        bot.setDmEnabled(false);
        var response = new ChatPostMessageResponse();
        response.setOk(true); response.setChannel("CTEST"); response.setTs("123.456");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(response);
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            tasks.createFromTool("after commit task " + meeting.getId(), null, null, meeting);
            return new AgentResult("완료", 1, 1, 0);
        });
    }

    // AFTER_COMMIT에서 게시한 메시지 좌표와 업무 집합은 요청 종료 후 별도 DB 조회에도 남아야 한다.
    @Test void postedMessageReferenceCommitsAfterMeetingCompletion() throws Exception {
        mvc.perform(post("/api/meetings").contentType("application/json")
                        .content("{\"rawText\":\"after commit persistence test\"}"))
                .andExpect(status().isOk());
        assertThat(references.findByChannelIdAndMessageTs("CTEST", "123.456")).isPresent();
        Meeting meeting = meetings.findAll().stream().filter(m -> m.getRawText().equals("after commit persistence test")).findFirst().orElseThrow();
        assertThat(meeting.getSlackChannelId()).isEqualTo("CTEST");
        assertThat(meeting.getSlackMessageTs()).isEqualTo("123.456");
    }
    @Test void slackDmAudioPersistsTeamResultAndInputReceiptSeparately() throws Exception {
        // 음성 입력 DM에는 링크만 남기고 팀 채널의 완료 버튼·담당자 DM 참조를 별도로 커밋한다.
        prepareDmRouting("UVOICEASSIGNEE", "DVOICEASSIGNEE");
        var response = application.submitAudioFromSlack(new com.meetodo.meeting.dto.MeetingRequest("DM 음성 팀 채널 결과 회귀"),
                "mock-stt", 10, meeting -> bot.prepareInput("DVOICEORIGIN", "321.123").attach(meeting), bot::discardInputProgress);
        var meeting = meetings.findById(response.meetingId()).orElseThrow();
        assertThat(meeting.getSource()).isEqualTo(MeetingSource.SLACK_AUDIO);
        assertThat(meeting.getSlackInputChannelId()).isEqualTo("DVOICEORIGIN");
        assertThat(meeting.getSlackInputMessageTs()).isEqualTo("321.123");
        assertDmPublication(meeting, "DVOICEORIGIN", "321.123", "DVOICEASSIGNEE");
    }

    @Test void slackDmTextPersistsTeamResultAndInputReceiptSeparately() throws Exception {
        // DM /meetodo 입력도 진행 메시지는 DM, 전체 결과와 완료 버튼은 설정한 팀 채널에 남긴다.
        prepareDmRouting("UTEXTASSIGNEE", "DTEXTASSIGNEE");
        var response = application.submitFromSlack(new com.meetodo.meeting.dto.MeetingRequest("DM 텍스트 팀 채널 결과 회귀"),
                meeting -> bot.startInput(meeting, "DTEXTORIGIN"), bot::discardInputProgress);
        var meeting = meetings.findById(response.meetingId()).orElseThrow();
        assertThat(meeting.getSource()).isEqualTo(MeetingSource.SLACK_TEXT);
        assertThat(meeting.getSlackInputChannelId()).isEqualTo("DTEXTORIGIN");
        assertThat(meeting.getSlackInputMessageTs()).isEqualTo("345.678");
        assertDmPublication(meeting, "DTEXTORIGIN", "345.678", "DTEXTASSIGNEE");
    }

    @Test void slackDmAgentFailureUpdatesOnlyInputAndDeletesTeamPlaceholder() throws Exception {
        // LLM 실패는 입력자 DM에서만 알리고 빈 팀 채널 진행 메시지와 버튼 참조를 남기지 않는다.
        prepareDmRouting("UFAILASSIGNEE", "DFAILASSIGNEE");
        when(agent.process(any())).thenThrow(new MeetingAgentException("LLM_ERROR", 1, 0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> application.submitFromSlack(
                new com.meetodo.meeting.dto.MeetingRequest("DM 실패 원문 전체"),
                meeting -> bot.startInput(meeting, "DFAILORIGIN"), bot::discardInputProgress))
                .isInstanceOf(MeetingProcessingException.class);
        var meeting = meetings.findByContentHash(Meeting.create("DM 실패 원문 전체", MeetingSource.SLACK_TEXT).getContentHash()).orElseThrow();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.FAILED);
        var update = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class);
        verify(client).chatUpdate(update.capture());
        assertThat(update.getValue().getChannel()).isEqualTo("DFAILORIGIN");
        assertThat(update.getValue().getText()).contains("❌ 회의록 정리에 실패했어요").doesNotContain("DM 실패 원문 전체", "LLM_ERROR");
        var delete = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatDeleteRequest.class);
        verify(client).chatDelete(delete.capture());
        assertThat(delete.getValue().getChannel()).isEqualTo("CTEST");
        assertThat(delete.getValue().getTs()).isEqualTo("123.456");
        assertThat(references.count()).isZero();
        verify(client, never()).conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class));
    }

    @Test void slackDmTeamInvitationFailureStoresNoMeetingAndNeverRunsAgent() throws Exception {
        // DM 진행 메시지 전송이 성공해도 팀 채널에 게시할 수 없으면 해시·회의를 선점하지 않는다.
        prepareDmRouting("UINVITEASSIGNEE", "DINVITEASSIGNEE");
        long before = meetings.count();
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(invocation -> {
            ChatPostMessageRequest request = invocation.getArgument(0);
            var posted = new ChatPostMessageResponse();
            if ("CTEST".equals(request.getChannel())) posted.setError("not_in_channel");
            else { posted.setOk(true); posted.setChannel(request.getChannel()); posted.setTs("345.678"); }
            return posted;
        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> application.submitFromSlack(
                new com.meetodo.meeting.dto.MeetingRequest("DM 팀 미초대 저장 금지"),
                meeting -> bot.startInput(meeting, "DINVITEORIGIN"), bot::discardInputProgress))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThat(meetings.count()).isEqualTo(before);
        assertThat(references.count()).isZero();
        verify(agent, never()).process(any());
    }

    @Test void duplicateDmInputPreservesOldCoordinatesWithoutPostingAgain() throws Exception {
        // 같은 본문 재전송은 기존 입력·팀 채널 좌표를 보존하고 진행 메시지와 LLM을 재실행하지 않는다.
        prepareDmRouting("UDUPASSIGNEE", "DDUPASSIGNEE");
        var request = new com.meetodo.meeting.dto.MeetingRequest("DM 입력 동일 해시 재전송 좌표 보존");
        var original = application.submitFromSlack(request, meeting -> bot.startInput(meeting, "DOLDORIGIN"), bot::discardInputProgress);
        org.mockito.Mockito.clearInvocations(client, agent);
        var repeated = application.submitFromSlack(request, meeting -> bot.startInput(meeting, "DNEWORIGIN"), bot::discardInputProgress);
        assertThat(repeated.meetingId()).isEqualTo(original.meetingId());
        var stored = meetings.findById(original.meetingId()).orElseThrow();
        assertThat(stored.getSlackInputChannelId()).isEqualTo("DOLDORIGIN");
        assertThat(stored.getSlackInputMessageTs()).isEqualTo("345.678");
        assertThat(stored.getSlackChannelId()).isEqualTo("CTEST");
        assertThat(stored.getSlackMessageTs()).isEqualTo("123.456");
        verify(client, never()).chatPostMessage(any(ChatPostMessageRequest.class));
        verify(client, never()).chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class));
        verify(client, never()).chatDelete(any(com.slack.api.methods.request.chat.ChatDeleteRequest.class));
        verify(agent, never()).process(any());
    }

    private void prepareDmRouting(String assigneeId, String assigneeDm) throws Exception {
        bot.setDmEnabled(true);
        var member = members.saveAndFlush(new com.meetodo.member.Member("전", assigneeId, "백엔드팀", assigneeId));
        when(agent.process(any(Meeting.class))).thenAnswer(call -> {
            Meeting meeting = call.getArgument(0);
            tasks.createFromTool("DM 입력 업무 " + meeting.getId(), member, null, meeting);
            return new AgentResult("완료", 1, 1, 0);
        });
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(invocation -> {
            ChatPostMessageRequest request = invocation.getArgument(0);
            var posted = new ChatPostMessageResponse(); posted.setOk(true); posted.setChannel(request.getChannel());
            posted.setTs("CTEST".equals(request.getChannel()) ? "123.456" : assigneeDm.equals(request.getChannel()) ? "234.567" : "345.678");
            return posted;
        });
        var updated = new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        var deleted = new com.slack.api.methods.response.chat.ChatDeleteResponse(); deleted.setOk(true);
        when(client.chatDelete(any(com.slack.api.methods.request.chat.ChatDeleteRequest.class))).thenReturn(deleted);
        var opened = new com.slack.api.methods.response.conversations.ConversationsOpenResponse(); opened.setOk(true);
        var channel = new com.slack.api.model.Conversation(); channel.setId(assigneeDm); opened.setChannel(channel);
        when(client.conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class))).thenReturn(opened);
        var link = new com.slack.api.methods.response.chat.ChatGetPermalinkResponse(); link.setOk(true);
        link.setPermalink("https://meetodo.slack.com/archives/CTEST/p123456");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(link);
    }

    private void assertDmPublication(Meeting meeting, String inputChannel, String inputTs, String assigneeDm) throws Exception {
        assertThat(meeting.getSlackChannelId()).isEqualTo("CTEST");
        assertThat(meeting.getSlackMessageTs()).isEqualTo("123.456");
        var updates = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).chatUpdate(updates.capture());
        var team = updates.getAllValues().stream().filter(request -> "CTEST".equals(request.getChannel())).findFirst().orElseThrow();
        assertThat(team.getTs()).isEqualTo("123.456");
        assertThat(team.getBlocksAsString()).contains("회의 정리 완료", "meetodo_complete_task");
        var receipt = updates.getAllValues().stream().filter(request -> inputChannel.equals(request.getChannel())).findFirst().orElseThrow();
        assertThat(receipt.getTs()).isEqualTo(inputTs);
        assertThat(receipt.getText()).isEqualTo("✅ 회의 정리 완료 (할 일 1건) → <https://meetodo.slack.com/archives/CTEST/p123456|채널 메시지>");
        assertThat(receipt.getBlocksAsString()).isEqualTo("[]");
        var ref = references.findByChannelIdAndMessageTs("CTEST", "123.456").orElseThrow();
        assertThat(new ObjectMapper().readTree(ref.getTaskIds())).hasSize(1);
        assertThat(references.findByChannelIdAndMessageTs(inputChannel, inputTs)).isEmpty();
        var dm = references.findByChannelIdAndMessageTs(assigneeDm, "234.567").orElseThrow();
        assertThat(dm.getMessageType()).isEqualTo(SlackMessageReference.MessageType.DM);
        assertThat(dm.getPermalink()).isEqualTo("https://meetodo.slack.com/archives/CTEST/p123456");
        var links = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.atLeastOnce()).chatGetPermalink(links.capture());
        assertThat(links.getAllValues()).allSatisfy(request -> {
            assertThat(request.getChannel()).isEqualTo("CTEST");
            assertThat(request.getMessageTs()).isEqualTo("123.456");
        });
        var posts = org.mockito.ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.atLeastOnce()).chatPostMessage(posts.capture());
        assertThat(posts.getAllValues()).filteredOn(request -> assigneeDm.equals(request.getChannel())).singleElement()
                .satisfies(request -> assertThat(request.getBlocksAsString()).contains("https://meetodo.slack.com/archives/CTEST/p123456"));
    }

    // 실제 DB에서 다시 읽은 참조를 클릭해 완료·현재 상태 조회·원래 메시지 갱신까지 검증한다.
    @Test void clickCompletesStoredTaskAndRebuildsMessageFromDatabase() throws Exception {
        var updated = new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        mvc.perform(post("/api/meetings").contentType("application/json")
                .content("{\"rawText\":\"button database completion test\"}")).andExpect(status().isOk());
        var reference = references.findByChannelIdAndMessageTs("CTEST", "123.456").orElseThrow();
        Long taskId = new ObjectMapper().readTree(reference.getTaskIds()).get(0).asLong();
        bot.complete("CTEST", "123.456", "UACTOR", taskId.toString());
        Task stored = taskRepository.findById(taskId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(stored.getCompletedBySlackUserId()).isEqualTo("UACTOR");
        var request = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class);
        verify(client).chatUpdate(request.capture());
        assertThat(request.getValue().getBlocksAsString()).contains("완료 (<@UACTOR>)").doesNotContain("meetodo_complete_task");
        verify(client, never()).chatPostEphemeral(any(com.slack.api.methods.request.chat.ChatPostEphemeralRequest.class));
    }

    @Test void dmFailureAfterCommitLeavesMeetingAndChannelSent() throws Exception {
        // 실제 AFTER_COMMIT 경로에서 DM 실패 기록만 남기고 채널 SENT·회의 DONE을 유지한다.
        bot.setDmEnabled(true);
        var assignee = members.saveAndFlush(new com.meetodo.member.Member("전", "해찬", "백엔드팀", "UACTOR"));
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            tasks.createFromTool("DM failed after commit " + meeting.getId(), assignee, null, meeting);
            return new AgentResult("완료", 1, 1, 0);
        });
        var link = new com.slack.api.methods.response.chat.ChatGetPermalinkResponse();
        link.setOk(true); link.setPermalink("https://meetodo.slack.com/archives/CTEST/p123456");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(link);
        var failure = new com.slack.api.methods.response.conversations.ConversationsOpenResponse();
        failure.setError("missing_scope");
        when(client.conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class))).thenReturn(failure);
        mvc.perform(post("/api/meetings").contentType("application/json")
                .content("{\"rawText\":\"DM failure independent commit test\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("DONE"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.notificationStatus").value("SENT"));
        assertThat(references.findAll()).filteredOn(ref -> ref.getMessageType() == SlackMessageReference.MessageType.DM)
                .singleElement().satisfies(ref -> {
                    assertThat(ref.getDeliveryStatus()).isEqualTo(SlackMessageReference.DeliveryStatus.FAILED);
                    assertThat(ref.getErrorCode()).isEqualTo("missing_scope");
                    assertThat(ref.getTargetSlackUserId()).isEqualTo("UACTOR");
                });
    }

    @Test void persistedDmClickUpdatesBothCommittedLocations() throws Exception {
        // 별도 커밋된 DM 좌표로 클릭해 DB 완료와 채널·DM 갱신을 함께 검증한다.
        bot.setDmEnabled(true);
        var assignee = members.saveAndFlush(new com.meetodo.member.Member("김", "테스터", "개발팀", "UDMTEST"));
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            tasks.createFromTool("DM success after commit " + meeting.getId(), assignee, null, meeting);
            return new AgentResult("완료", 1, 1, 0);
        });
        var link = new com.slack.api.methods.response.chat.ChatGetPermalinkResponse();
        link.setOk(true); link.setPermalink("https://meetodo.slack.com/archives/CTEST/p123456");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(link);
        var conversation = new com.slack.api.model.Conversation(); conversation.setId("DDMTEST");
        var opened = new com.slack.api.methods.response.conversations.ConversationsOpenResponse();
        opened.setOk(true); opened.setChannel(conversation);
        when(client.conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class))).thenReturn(opened);
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(invocation -> {
            ChatPostMessageRequest request = invocation.getArgument(0);
            var response = new ChatPostMessageResponse(); response.setOk(true); response.setChannel(request.getChannel());
            response.setTs(request.getChannel().equals("CTEST") ? "123.456" : "234.567"); return response;
        });
        var updated = new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        mvc.perform(post("/api/meetings").contentType("application/json")
                .content("{\"rawText\":\"DM success independent commit test\"}")).andExpect(status().isOk());
        var dm = references.findByChannelIdAndMessageTs("DDMTEST", "234.567").orElseThrow();
        assertThat(dm.getMessageType()).isEqualTo(SlackMessageReference.MessageType.DM);
        Long taskId = new ObjectMapper().readTree(dm.getTaskIds()).get(0).asLong();
        bot.complete("DDMTEST", "234.567", "UDMTEST", taskId.toString());
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        var updates = org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).extracting(com.slack.api.methods.request.chat.ChatUpdateRequest::getChannel)
                .containsExactlyInAnyOrder("CTEST", "DDMTEST");
        assertThat(updates.getAllValues()).allSatisfy(request ->
                assertThat(request.getBlocksAsString()).contains("완료 (<@UDMTEST>)").doesNotContain("meetodo_complete_task"));
    }

    @Test void slackSubmissionUpdatesProgressInOriginChannelAndSendsNewAssignmentDm() throws Exception {
        // Slack 입력도 기존 알림·DM 경계를 재사용하고 설정 채널이 아닌 원래 채널의 진행 메시지를 수정한다.
        bot.setDmEnabled(true);
        var assignee = members.saveAndFlush(new com.meetodo.member.Member("전", "슬랙입력", "개발팀", "USLACKINPUT"));
        when(agent.process(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting=invocation.getArgument(0);
            tasks.createFromTool("슬랙 신규 업무 " + meeting.getId(),assignee,null,meeting);
            return new AgentResult("완료",1,1,0);
        });
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(invocation -> {
            ChatPostMessageRequest request=invocation.getArgument(0);
            var response=new ChatPostMessageResponse(); response.setOk(true); response.setChannel(request.getChannel());
            response.setTs(request.getChannel().equals("CORIGIN") ? "345.678" : "456.789"); return response;
        });
        var updated=new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        var link=new com.slack.api.methods.response.chat.ChatGetPermalinkResponse(); link.setOk(true);
        link.setPermalink("https://meetodo.slack.com/archives/CORIGIN/p345678");
        when(client.chatGetPermalink(any(com.slack.api.methods.request.chat.ChatGetPermalinkRequest.class))).thenReturn(link);
        var conversation=new com.slack.api.model.Conversation(); conversation.setId("DSLACKINPUT");
        var opened=new com.slack.api.methods.response.conversations.ConversationsOpenResponse(); opened.setOk(true); opened.setChannel(conversation);
        when(client.conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class))).thenReturn(opened);
        var response = application.submitFromSlack(new com.meetodo.meeting.dto.MeetingRequest("Slack input integration"),m -> bot.startInput(m,"CORIGIN"),bot::discardInputProgress);
        assertThat(response.status()).isEqualTo(MeetingStatus.DONE);
        assertThat(response.notificationStatus()).isEqualTo(NotificationStatus.SENT);
        var posts=org.mockito.ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).chatPostMessage(posts.capture());
        assertThat(posts.getAllValues().getFirst().getChannel()).isEqualTo("CORIGIN");
        assertThat(posts.getAllValues().getFirst().getText()).isEqualTo("⏳ 회의록 정리 중...");
        assertThat(posts.getAllValues().getLast().getChannel()).isEqualTo("DSLACKINPUT");
        var update=org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class); verify(client).chatUpdate(update.capture());
        assertThat(update.getValue().getChannel()).isEqualTo("CORIGIN"); assertThat(update.getValue().getTs()).isEqualTo("345.678");
        assertThat(update.getValue().getBlocksAsString()).contains("회의 정리 완료","meetodo_complete_task");
        var ref=references.findByChannelIdAndMessageTs("CORIGIN","345.678").orElseThrow();
        assertThat(new ObjectMapper().readTree(ref.getTaskIds())).hasSize(1);
        assertThat(references.findByChannelIdAndMessageTs("DSLACKINPUT","456.789")).isPresent();
    }

    @Test void slackAgentFailureReplacesOriginalProgressWithGenericFailure() throws Exception {
        // LLM 실패도 새 게시 대신 같은 ts를 수정하고 원문·공급자 오류를 노출하지 않는다.
        var progress=new ChatPostMessageResponse(); progress.setOk(true); progress.setChannel("CORIGIN"); progress.setTs("567.890");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(progress);
        var updated=new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        when(agent.process(any())).thenThrow(new MeetingAgentException("LLM_ERROR",1,0));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> application.submitFromSlack(
                new com.meetodo.meeting.dto.MeetingRequest("원문전체실패확인"),m -> bot.startInput(m,"CORIGIN"),bot::discardInputProgress))
                .isInstanceOf(MeetingProcessingException.class);
        var update=org.mockito.ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatUpdateRequest.class); verify(client).chatUpdate(update.capture());
        assertThat(update.getValue().getChannel()).isEqualTo("CORIGIN"); assertThat(update.getValue().getTs()).isEqualTo("567.890");
        assertThat(update.getValue().getText()).contains("❌ 회의록 정리에 실패했어요 (회의 ").doesNotContain("원문전체실패확인","LLM_ERROR");
        verify(client).chatPostMessage(any(ChatPostMessageRequest.class));
        verify(client,never()).conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class));
    }

    @Test void missingBotLeavesNoMeetingAndSameBodyWorksAfterInvitation() throws Exception {
        // 실제 입력 서비스·JPA 경로에서 미초대 안내 뒤 같은 본문을 처리하고 완료 재전송은 중복 안내한다.
        long before=meetings.count(); var replies=new java.util.ArrayList<String>();
        var input=new SlackMeetingInputService(client,application,bot,Runnable::run,
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-12T00:00:00Z"),java.time.ZoneId.of("Asia/Seoul")));
        var modal=new com.slack.api.methods.response.views.ViewsOpenResponse(); modal.setOk(true);
        modal.setView(com.slack.api.model.view.View.builder().id("VRETRY1").build());
        when(client.viewsOpen(any(com.slack.api.methods.request.views.ViewsOpenRequest.class))).thenReturn(modal);
        var denied=new ChatPostMessageResponse(); denied.setError("not_in_channel");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(denied);
        String text="새 게시 순서 같은 본문 실제 JPA";
        input.open("trigger1","CRETRY","UACTOR",replies::add); input.submit("VRETRY1","CRETRY","UACTOR",text);
        assertThat(meetings.count()).isEqualTo(before); assertThat(references.count()).isZero();
        assertThat(replies).containsExactly("/invite @MeetoDo 후 다시 시도해 주세요.");
        verify(agent,never()).process(any());
        var progress=new ChatPostMessageResponse(); progress.setOk(true); progress.setChannel("CRETRY"); progress.setTs("678.901");
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(progress);
        var updated=new com.slack.api.methods.response.chat.ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class))).thenReturn(updated);
        modal.setView(com.slack.api.model.view.View.builder().id("VRETRY2").build());
        input.open("trigger2","CRETRY","UACTOR",replies::add); input.submit("VRETRY2","CRETRY","UACTOR",text);
        assertThat(meetings.count()).isEqualTo(before+1);
        var meeting=meetings.findByContentHash(Meeting.create(text,MeetingSource.SLACK_TEXT).getContentHash()).orElseThrow();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.DONE);
        modal.setView(com.slack.api.model.view.View.builder().id("VRETRY3").build());
        input.open("trigger3","CRETRY","UACTOR",replies::add); input.submit("VRETRY3","CRETRY","UACTOR",text);
        assertThat(replies.getLast()).isEqualTo("이미 정리된 회의록입니다 (회의 " + meeting.getId() + ")");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).chatPostMessage(any(ChatPostMessageRequest.class));
        verify(client).chatUpdate(any(com.slack.api.methods.request.chat.ChatUpdateRequest.class));
        verify(agent).process(any()); assertThat(meetings.count()).isEqualTo(before+1);
    }

}
