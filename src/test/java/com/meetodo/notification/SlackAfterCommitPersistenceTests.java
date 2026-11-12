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

}
