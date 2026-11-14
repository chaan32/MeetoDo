package com.meetodo.slack;

import com.google.gson.JsonParser;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.registration.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.*;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.methods.response.chat.*;
import com.slack.api.methods.response.conversations.ConversationsOpenResponse;
import com.slack.api.model.Conversation;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SlackRegistrationNotificationsTests {
    private final MethodsClient client = mock(MethodsClient.class);
    private final MemberRegistrationService registrations = mock(MemberRegistrationService.class);
    private final AdminAuthorization admins = mock(AdminAuthorization.class);
    private final SlackRegistrationMessageRepository messages = mock(SlackRegistrationMessageRepository.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SlackHomeService homes = mock(SlackHomeService.class);
    private final List<SlackRegistrationMessage> stored = new ArrayList<>();
    private final SlackRegistrationNotifications service = new SlackRegistrationNotifications(
            client, registrations, admins, messages, manager, homes);

    @BeforeEach void setUp() throws Exception {
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(admins.ids()).thenReturn(new LinkedHashSet<>(List.of("UADMIN1", "UADMIN2")));
        when(messages.findAllByRequestId(7L)).thenAnswer(call -> new ArrayList<>(stored));
        when(messages.save(any())).thenAnswer(call -> {
            SlackRegistrationMessage message = call.getArgument(0);
            if (!stored.contains(message)) stored.add(message);
            return message;
        });
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenAnswer(call -> {
            ConversationsOpenRequest request = call.getArgument(0);
            var channel = new Conversation(); channel.setId("D" + request.getUsers().getFirst());
            var response = new ConversationsOpenResponse(); response.setOk(true); response.setChannel(channel); return response;
        });
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(call -> {
            ChatPostMessageRequest request = call.getArgument(0);
            var response = new ChatPostMessageResponse(); response.setOk(true); response.setChannel(request.getChannel());
            response.setTs("1.2"); return response;
        });
        var updated = new ChatUpdateResponse(); updated.setOk(true);
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenReturn(updated);
        when(registrations.getForNotification(7L)).thenReturn(view(RegistrationStatus.PENDING, false));
    }

    @Test void pendingSendsEveryAdminAnIndependentApprovalMessage() throws Exception {
        // 모든 관리자의 DM에 동일 요청 ID를 가진 세 버튼과 중복 이름 경고를 보낸다.
        when(registrations.getForNotification(7L)).thenReturn(view(RegistrationStatus.PENDING, true));
        service.changed(7L);
        var posts = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client, times(2)).chatPostMessage(posts.capture());
        assertThat(posts.getAllValues()).extracting(ChatPostMessageRequest::getChannel).containsExactly("DUADMIN1", "DUADMIN2");
        for (var post : posts.getAllValues()) {
            assertThat(post.getBlocksAsString()).contains("meetodo_registration_approve", "meetodo_registration_edit",
                    "meetodo_registration_reject", "같은 이름", "\"value\":\"7\"");
        }
        assertThat(stored).hasSize(2).allMatch(message -> message.getStatus() == SlackRegistrationMessage.Status.SENT);
        verify(homes).refreshAll();
    }

    @Test void oneAdminFailureDoesNotPreventOtherAdminDelivery(CapturedOutput output) throws Exception {
        // 공급자 원문을 숨기고 실패한 관리자만 FAILED로 남기며 다음 관리자에게 계속 보낸다.
        String secret = String.join("", "xox", "b-private provider-body");
        when(client.conversationsOpen(any(ConversationsOpenRequest.class))).thenAnswer(call -> {
            ConversationsOpenRequest request = call.getArgument(0);
            if (request.getUsers().contains("UADMIN1")) throw new java.io.IOException(secret);
            var channel = new Conversation(); channel.setId("DUADMIN2");
            var response = new ConversationsOpenResponse(); response.setOk(true); response.setChannel(channel); return response;
        });
        service.changed(7L);
        assertThat(stored.getFirst().getErrorCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(stored.get(1).getStatus()).isEqualTo(SlackRegistrationMessage.Status.SENT);
        assertThat(output.getAll()).doesNotContain(secret, "provider-body");
        verify(client).chatPostMessage(any(ChatPostMessageRequest.class));
    }

    @Test void terminalUpdatesEverySentAdminMessageAndNotifiesApplicant() throws Exception {
        // 결정 이후 저장된 모든 성공 DM에서 버튼을 없애고 실제 처리자를 표시한다.
        addSent("UADMIN1"); addSent("UADMIN2");
        when(registrations.getForNotification(7L)).thenReturn(view(RegistrationStatus.APPROVED, false));
        service.changed(7L);
        var updates = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client, times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).allSatisfy(update -> {
            assertThat(update.getText()).isEqualTo("처리됨(승인, <@UACTOR>)");
            assertThat(update.getBlocksAsString()).doesNotContain("actions", "button");
        });
        var post = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client).chatPostMessage(post.capture());
        assertThat(post.getValue().getChannel()).isEqualTo("DUAPPLICANT");
        assertThat(post.getValue().getText()).isEqualTo("팀원으로 등록됐어요");
        verify(homes).refreshAll();
    }

    @Test void approvalDuringSecondPendingPostRemovesBothAdminButtonsWithoutDuplicateApplicantDm() throws Exception {
        // 두 번째 DM 저장 전에 승인이 완료돼도 두 관리자 메시지를 모두 처리됨으로 바꾼다.
        RegistrationView[] current = {view(RegistrationStatus.PENDING, false)};
        when(registrations.getForNotification(7L)).thenAnswer(call -> current[0]);
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenAnswer(call -> {
            ChatPostMessageRequest request = call.getArgument(0);
            if (request.getChannel().equals("DUADMIN2")) {
                assertThat(stored).extracting(SlackRegistrationMessage::getAdminSlackUserId).containsExactly("UADMIN1");
                current[0] = view(RegistrationStatus.APPROVED, false);
                service.changed(7L);
            }
            var response = new ChatPostMessageResponse(); response.setOk(true); response.setChannel(request.getChannel());
            response.setTs("1.2"); return response;
        });
        service.changed(7L);
        var updates = ArgumentCaptor.forClass(ChatUpdateRequest.class);
        verify(client, times(2)).chatUpdate(updates.capture());
        assertThat(updates.getAllValues()).extracting(ChatUpdateRequest::getChannel).containsExactly("DUADMIN1", "DUADMIN2");
        assertThat(updates.getAllValues()).allSatisfy(update -> {
            assertThat(update.getText()).isEqualTo("처리됨(승인, <@UACTOR>)");
            assertThat(update.getBlocksAsString()).doesNotContain("button", "actions");
        });
        var posts = ArgumentCaptor.forClass(ChatPostMessageRequest.class);
        verify(client, times(3)).chatPostMessage(posts.capture());
        assertThat(posts.getAllValues()).filteredOn(post -> post.getChannel().equals("DUAPPLICANT"))
                .singleElement().satisfies(post -> assertThat(post.getText()).isEqualTo("팀원으로 등록됐어요"));
        assertThat(stored).hasSize(2).allMatch(message -> message.getStatus() == SlackRegistrationMessage.Status.SENT);
    }

    @Test void partialUpdateFailureStillNotifiesApplicantAndRefreshesHome() throws Exception {
        // 한 관리자 메시지 수정 실패는 다른 관리자·신청자·Home의 갱신을 막지 않는다.
        addSent("UADMIN1"); addSent("UADMIN2");
        when(registrations.getForNotification(7L)).thenReturn(view(RegistrationStatus.REJECTED, false));
        when(client.chatUpdate(any(ChatUpdateRequest.class))).thenAnswer(call -> {
            ChatUpdateRequest request = call.getArgument(0);
            var response = new ChatUpdateResponse(); response.setOk(!request.getChannel().equals("DUADMIN1"));
            response.setError("message_not_found"); return response;
        });
        service.changed(7L);
        assertThat(stored.getFirst().getErrorCode()).isEqualTo("message_not_found");
        assertThat(stored.get(1).getStatus()).isEqualTo(SlackRegistrationMessage.Status.SENT);
        verify(client, times(2)).chatUpdate(any(ChatUpdateRequest.class));
        verify(client).chatPostMessage(any(ChatPostMessageRequest.class)); verify(homes).refreshAll();
    }

    @Test void storedAdminDeliveryIsNotSentAgain() throws Exception {
        // 재통지 시 이미 저장된 관리자 DM을 중복 생성하지 않는다.
        addSent("UADMIN1"); addSent("UADMIN2"); service.changed(7L);
        verify(client, never()).conversationsOpen(any(ConversationsOpenRequest.class));
    }

    @Test void nameAndTeamUsePlainTextAndFallbackEscapesUserMarkup() throws Exception {
        // 사용자 이름과 팀의 Slack 특수문법은 멘션이나 링크로 해석하지 않는다.
        when(registrations.getForNotification(7L)).thenReturn(new RegistrationView(7L, "UAPPLICANT", "<!here>",
                "<@UOTHER>", "<https://example.com|팀>", RegistrationStatus.PENDING, null, null, false, null));
        service.changed(7L);
        var posts = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client, times(2)).chatPostMessage(posts.capture());
        var post = posts.getValue();
        var text = JsonParser.parseString(post.getBlocksAsString()).getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("text");
        assertThat(text.get("type").getAsString()).isEqualTo("plain_text");
        assertThat(post.getText()).contains("&lt;!here&gt;", "&lt;@UOTHER&gt;", "<@UAPPLICANT>");
        assertThat(post.getParse()).isEqualTo("none");
    }

    @Test void rejectionReasonIsPlainTextAndEscapedInFallback() throws Exception {
        // 거절 사유에 담긴 멘션 구문은 신청자에게 일반 텍스트로 보인다.
        when(registrations.getForNotification(7L)).thenReturn(new RegistrationView(7L, "UAPPLICANT", "김", "민수", "팀",
                RegistrationStatus.REJECTED, "UACTOR", "<!channel> <@UOTHER>", false, null));
        service.changed(7L);
        var post = ArgumentCaptor.forClass(ChatPostMessageRequest.class); verify(client).chatPostMessage(post.capture());
        assertThat(post.getValue().getText()).contains("&lt;!channel&gt; &lt;@UOTHER&gt;");
        assertThat(post.getValue().getBlocksAsString()).contains("plain_text");
    }

    @Test void applicantTransportFailureStillRefreshesHome(CapturedOutput output) throws Exception {
        // 신청자 DM 전송 실패도 Home 갱신을 막거나 공급자 메시지를 노출하지 않는다.
        when(registrations.getForNotification(7L)).thenReturn(view(RegistrationStatus.APPROVED, false));
        String privateMessage = String.join("", "xox", "b-applicant-private");
        when(client.conversationsOpen(any(ConversationsOpenRequest.class)))
                .thenThrow(new java.io.IOException(privateMessage));
        assertThatCode(() -> service.changed(7L)).doesNotThrowAnyException();
        verify(homes).refreshAll();
        assertThat(output.getAll()).contains("INTERNAL_ERROR").doesNotContain(privateMessage);
    }

    @Test void unknownProviderErrorIsNeverStoredVerbatim() throws Exception {
        // 허용 목록 밖의 공급자 오류 문자열은 DB에도 일반 오류 코드만 남긴다.
        var failure = new ChatPostMessageResponse();
        failure.setError(String.join("", "https://hooks.slack.com/services/", "private-provider-value"));
        when(client.chatPostMessage(any(ChatPostMessageRequest.class))).thenReturn(failure);
        service.changed(7L);
        assertThat(stored).hasSize(2).allSatisfy(message -> {
            assertThat(message.getStatus()).isEqualTo(SlackRegistrationMessage.Status.FAILED);
            assertThat(message.getErrorCode()).isEqualTo("INTERNAL_ERROR");
        });
    }

    @Test void invalidApplicantIdentityCannotInjectMentionOrOpenDm() throws Exception {
        // 잘못된 Slack ID로는 임의 멘션을 출력하거나 대화 API를 호출하지 않는다.
        when(registrations.getForNotification(7L)).thenReturn(new RegistrationView(7L, "UAPPLICANT> <!here", "김", "민수", "팀",
                RegistrationStatus.APPROVED, "UACTOR", null, false, 3L));
        service.changed(7L);
        verify(client, never()).conversationsOpen(any(ConversationsOpenRequest.class));
        verify(client, never()).chatPostMessage(any(ChatPostMessageRequest.class));
        verify(homes).refreshAll();
    }

    private void addSent(String admin) { stored.add(new SlackRegistrationMessage(7L, admin, "D" + admin, "1.2", null)); }
    private RegistrationView view(RegistrationStatus status, boolean duplicate) {
        return new RegistrationView(7L, "UAPPLICANT", "김", "민수", "백엔드", status,
                status == RegistrationStatus.PENDING ? null : "UACTOR", "정보 확인이 필요해요", duplicate, null);
    }
}
