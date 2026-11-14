package com.meetodo.member.registration;

import com.meetodo.member.*;
import com.meetodo.meeting.*;
import com.meetodo.slack.*;
import com.meetodo.tools.FindMemberTool;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.methods.response.chat.*;
import com.slack.api.methods.response.conversations.ConversationsOpenResponse;
import com.slack.api.model.Conversation;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:registration-workflow;DB_CLOSE_DELAY=-1",
        "MEETODO_ADMIN_SLACK_IDS=UADMIN1,UADMIN2"})
@ActiveProfiles({"test","registration-workflow"})
@Import(RegistrationWorkflowIntegrationTests.Notifications.class)
class RegistrationWorkflowIntegrationTests {
    @TestConfiguration(proxyBeanMethods=false) @Profile("registration-workflow")
    static class Notifications {
        @Bean SlackRegistrationNotifications registrationNotifications(MethodsClient client, MemberRegistrationService registrations,
                AdminAuthorization admins, SlackRegistrationMessageRepository messages, PlatformTransactionManager transactions, SlackHomeService homes) {
            return new SlackRegistrationNotifications(client, registrations, admins, messages, transactions, homes);
        }
    }
    @Autowired MemberRegistrationService registrations;
    @Autowired MemberRepository members;
    @Autowired SlackRegistrationMessageRepository messages;
    @Autowired JdbcTemplate jdbc;
    @Autowired FindMemberTool findMember;
    @Autowired MeetingRepository meetings;
    @MockitoBean MethodsClient client;
    @MockitoBean SlackHomeService homes;

    @BeforeEach void setup() throws Exception {
        jdbc.execute("DELETE FROM member_registration_messages");
        jdbc.execute("DELETE FROM member_registration_events");
        jdbc.execute("DELETE FROM member_registration_requests");
        jdbc.execute("DELETE FROM tool_call_logs");
        jdbc.execute("DELETE FROM meetings");
        members.deleteAll();
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenAnswer(call -> {
            var response=new ConversationsOpenResponse(); response.setOk(true);
            var channel=new Conversation(); channel.setId("D"+((ConversationsOpenRequest)call.getArgument(0)).getUsers().getFirst());
            response.setChannel(channel); return response;
        });
        AtomicInteger sequence=new AtomicInteger();
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(call -> {
            var response=new ChatPostMessageResponse(); response.setOk(true);
            response.setChannel(((ChatPostMessageRequest)call.getArgument(0)).getChannel());
            response.setTs(sequence.incrementAndGet()+".000001"); return response;
        });
        var update=new ChatUpdateResponse(); update.setOk(true);
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(update);
    }

    @Test void approvalUpdatesAllAdminMessagesAndNotifiesApplicantOnlyOnce() throws Exception {
        // 실제 커밋 이벤트로 관리자2명의 요청 DM을 갱신하고 중복 결정은 재발송하지 않는다.
        var request=registrations.submit("UAPPLICANT","홍","길동","프론트엔드팀");
        assertThat(messages.findAllByRequestId(request.id())).hasSize(2);
        assertThat(members.findBySlackUserIdAndActiveTrue("UAPPLICANT")).isEmpty();
        registrations.approve(request.id(),"UADMIN1");
        registrations.reject(request.id(),"UADMIN2","늦은 거절");
        assertThat(members.findBySlackUserIdAndActiveTrue("UAPPLICANT")).isPresent();
        var updates=org.mockito.ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client,times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).allSatisfy(update -> assertThat(update.getText()).contains("처리됨","승인","UADMIN1"));
        var posts=org.mockito.ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client,times(3)).chatPostMessage(posts.capture());
        assertThat(posts.getAllValues()).filteredOn(post -> "DUAPPLICANT".equals(post.getChannel())).hasSize(1)
                .allSatisfy(post -> assertThat(post.getText()).contains("팀원으로 등록됐어요"));
        verify(homes,atLeastOnce()).refreshAll();
    }

    @Test void pendingApplicantIsExcludedFromRealFindMemberUntilApproval() {
        // 신청 정보는 Member와 분리되어 실제 조회 도구에 승인 전 노출되지 않는다.
        var request=registrations.submit("UAPPLICANT","홍","길동","프론트엔드팀");
        var meeting=meetings.saveAndFlush(Meeting.create("길동은 초안을 검토한다.",MeetingSource.API_TEXT));
        assertThat(findMember.findMember("길동",meeting).errorCode()).isEqualTo("MEMBER_NOT_FOUND");
        registrations.approve(request.id(),"UADMIN1");
        var found=findMember.findMember("길동",meeting);
        assertThat(found.success()).isTrue();
        assertThat(found.data()).contains("홍길동","프론트엔드팀");
    }

    @Test void rejectedApplicantCanResubmitWithNewHistoryAndFreshAdminMessages() {
        // 거절 이력을 보존한 채 재신청을 새로운 요청으로 알린다.
        var first=registrations.submit("UAPPLICANT","홍","길동","프론트엔드팀");
        registrations.reject(first.id(),"UADMIN2","팀을 확인해 주세요");
        var second=registrations.submit("UAPPLICANT","홍","길동","디자인팀");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.status()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(messages.findAllByRequestId(first.id())).hasSize(2);
        assertThat(messages.findAllByRequestId(second.id())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_registration_events",Long.class)).isEqualTo(3L);
    }
}
