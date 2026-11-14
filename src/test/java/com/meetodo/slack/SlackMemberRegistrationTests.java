package com.meetodo.slack;

import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.registration.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.model.view.View;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SlackMemberRegistrationTests {
    final MethodsClient client = mock(MethodsClient.class);
    final MemberRegistrationService registrations = mock(MemberRegistrationService.class);
    final AdminAuthorization admins = mock(AdminAuthorization.class);
    final SlackHomeService homes = mock(SlackHomeService.class);
    final Clock clock = mock(Clock.class);
    SlackMemberRegistrationService ui;
    final RegistrationView pending = new RegistrationView(7L, "U1", "김", "민수", "개발", RegistrationStatus.PENDING, null, null, true, null);
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(Instant.EPOCH);
        when(registrations.teams()).thenReturn(List.of("개발"));
        when(registrations.get(7L, "UA")).thenReturn(pending);
        var response = new ViewsOpenResponse(); response.setOk(true); response.setView(View.builder().id("V1").build());
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenReturn(response);
        ui = new SlackMemberRegistrationService(client, registrations, admins, homes, clock);
    }
    @Test void applicantFormCannotSelectAnotherUser() throws Exception {
        // 신청자는 서버가 Slack 이벤트 사용자로 고정하며 성과 이름은 직접 입력한다.
        ui.open("trigger", "U1"); var request = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getViewAsString()).contains("familyName", "givenName", "static_select", "newTeam").doesNotContain("users_select", "private_metadata");
    }
    @Test void applicantUsesContextActor() {
        // 신청서에는 임의 Slack ID 필드가 없고 모달 소유자만 신청할 수 있다.
        ui.open("trigger", "U1"); ui.submit("V1", "U1", "김", "민수", "개발", null, null);
        verify(registrations).submit("U1", "김", "민수", "개발");
    }
    @Test void newTeamCanReplaceExistingChoice() {
        // 기존 팀을 선택하지 않은 경우 새 팀 이름 하나를 허용한다.
        ui.open("trigger", "U1"); ui.submit("V1", "U1", "김", "민수", null, "새팀", null);
        verify(registrations).submit("U1", "김", "민수", "새팀");
    }
    @Test void bothTeamInputsAreRejected() {
        // 두 팀 입력이 모순되면 한쪽을 추측하지 않는다.
        ui.open("trigger", "U1"); ui.submit("V1", "U1", "김", "민수", "개발", "새팀", null);
        verify(registrations, never()).submit(any(), any(), any(), any());
    }
    @Test void absentTeamIsRejected() {
        // 기존 팀과 새 팀 모두 없으면 신청하지 않는다.
        ui.open("trigger", "U1"); ui.submit("V1", "U1", "김", "민수", null, " ", null);
        verify(registrations, never()).submit(any(), any(), any(), any());
    }
    @Test void forgedTeamOptionIsRejected() {
        // 서버가 실제 표시한 팀 목록 밖의 선택값을 받지 않는다.
        ui.open("trigger", "U1"); ui.submit("V1", "U1", "김", "민수", "위조팀", null, null);
        verify(registrations, never()).submit(any(), any(), any(), any());
    }
    @Test void forgedViewCannotSubmit() {
        // private_metadata나 callback만 위조해도 서버 문맥 없이는 실행하지 않는다.
        ui.submit("FORGED", "U1", "김", "민수", "개발", null, null); verifyNoInteractions(registrations);
    }
    @Test void wrongActorDoesNotConsumeModal() {
        // 다른 사용자의 제출은 원 소유자의 정상 제출을 소진하지 않는다.
        ui.open("trigger", "U1"); ui.submit("V1", "U2", "김", "민수", "개발", null, null);
        ui.submit("V1", "U1", "김", "민수", "개발", null, null); verify(registrations).submit("U1", "김", "민수", "개발");
    }
    @Test void expiredViewCannotSubmit() {
        // 모달은 30분이 지나면 더 이상 신청을 변경하지 못한다.
        ui.open("trigger", "U1"); when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1800));
        ui.submit("V1", "U1", "김", "민수", "개발", null, null); verify(registrations, never()).submit(any(), any(), any(), any());
    }
    @Test void submissionIsConsumedOnce() {
        // Slack 제출 재전송은 한 번만 공통 서비스에 전달한다.
        ui.open("trigger", "U1"); for (int i=0;i<2;i++) ui.submit("V1", "U1", "김", "민수", "개발", null, null);
        verify(registrations, times(1)).submit("U1", "김", "민수", "개발");
    }
    @Test void closedModalCannotSubmit() {
        // 닫힌 모달의 지연 제출은 무시한다.
        ui.open("trigger", "U1"); ui.close("V1", "U1"); ui.submit("V1", "U1", "김", "민수", "개발", null, null);
        verify(registrations, never()).submit(any(), any(), any(), any());
    }
    @Test void duplicateNameIsWarningRatherThanRejection() throws Exception {
        // 동명이인은 승인 편집 화면에서 경고하고 Slack ID와 요청을 유지한다.
        ui.edit("trigger", "UA", "7"); var request=ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        assertThat(request.getValue().getViewAsString()).contains("동명이인", "김", "민수", "initial_option");
        ui.submit("V1", "UA", "김", "민수", "개발", null, null); verify(registrations).approve(7L,"UA","김","민수","개발");
    }
    @Test void directApprovalRechecksAdmin() {
        // 요청 버튼값을 알아도 현재 관리자 권한이 필요하다.
        ui.approve("UA", "7"); verify(admins).requireAdmin("UA"); verify(registrations).approve(7L,"UA");
    }
    @Test void nonAdminCannotApprove() {
        // UI의 직접 승인도 DB 서비스 호출 전에 관리자 권한을 검사한다.
        doThrow(new IllegalArgumentException()).when(admins).requireAdmin("U1"); ui.approve("U1","7"); verify(registrations,never()).approve(anyLong(),any());
    }
    @Test void rejectionReasonIsOptional() {
        // 사유 없는 거절도 모달 소유자와 저장된 요청 ID로 처리한다.
        ui.reject("trigger", "UA", "7"); ui.submit("V1", "UA", null,null,null,null,null); verify(registrations).reject(7L,"UA",null);
    }
    @Test void overlongRejectionReasonIsRejected() {
        // Slack 길이 제한을 우회한 사유도 300자로 검증한다.
        ui.reject("trigger", "UA", "7"); ui.submit("V1", "UA", null,null,null,null,"가".repeat(301)); verify(registrations,never()).reject(anyLong(),any(),any());
    }
    @Test void revokedAdminCannotSubmitEditor() {
        // 모달을 연 뒤 권한이 사라지면 수정 승인도 차단한다.
        ui.edit("trigger", "UA", "7"); doThrow(new IllegalArgumentException()).when(admins).requireAdmin("UA");
        ui.submit("V1", "UA", "김", "민수", "개발", null,null); verify(registrations,never()).approve(anyLong(),any(),any(),any(),any());
    }
    @Test void blankFamilyNameIsRejected() {
        // 필수 성을 공백으로 우회해도 이름을 추측하지 않는다.
        ui.open("trigger","U1"); ui.submit("V1","U1"," ","민수","개발",null,null);
        verify(registrations,never()).submit(any(),any(),any(),any());
    }
    @Test void blankGivenNameIsRejected() {
        // 필수 이름이 없으면 Slack 프로필로 채우지 않는다.
        ui.open("trigger","U1"); ui.submit("V1","U1","김","","개발",null,null);
        verify(registrations,never()).submit(any(),any(),any(),any());
    }
    @Test void overlongNameIsRejected() {
        // UI max_length를 우회한 81자 이름을 잘라 저장하지 않는다.
        ui.open("trigger","U1"); ui.submit("V1","U1","김","가".repeat(81),"개발",null,null);
        verify(registrations,never()).submit(any(),any(),any(),any());
    }
    @Test void exactNameLengthLimitIsAccepted() {
        // 최대 80자 입력은 공통 서비스 검증 경계까지 전달한다.
        ui.open("trigger","U1"); ui.submit("V1","U1","김","가".repeat(80),"개발",null,null);
        verify(registrations).submit("U1","김","가".repeat(80),"개발");
    }
    @Test void exactReasonLengthLimitIsAccepted() {
        // 거절 사유 최대 300자는 그대로 저장 계층에 전달한다.
        ui.reject("trigger","UA","7"); ui.submit("V1","UA",null,null,null,null,"가".repeat(300));
        verify(registrations).reject(7L,"UA","가".repeat(300));
    }
    @Test void processedRequestDoesNotOpenEditor() throws Exception {
        // 오래된 알림 버튼은 이미 처리된 신청의 편집 화면을 열지 않는다.
        when(registrations.get(7L,"UA")).thenReturn(new RegistrationView(7L,"U1","김","민수","개발",RegistrationStatus.APPROVED,"UB",null,false,1L));
        ui.edit("trigger","UA","7"); verify(client,never()).viewsOpen(any(ViewsOpenRequest.class));
        verify(homes).publish(eq("UA"),contains("이미 처리"));
    }
    @Test void failureReachesPrivateDmEvenWhenHomeWasNeverOpened() throws Exception {
        // Home 미개방 관리자에게도 권한 오류를 본인 DM으로 안내한다.
        var opened=new com.slack.api.methods.response.conversations.ConversationsOpenResponse(); opened.setOk(true);
        opened.setChannel(com.slack.api.model.Conversation.builder().id("DADMIN").build());
        when(client.conversationsOpen(any(com.slack.api.methods.request.conversations.ConversationsOpenRequest.class))).thenReturn(opened);
        doThrow(new com.meetodo.member.MemberManagementException(org.springframework.http.HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","관리자만 승인할 수 있어요.")).when(admins).requireAdmin("U1");
        ui.approve("U1","7");
        var sent=ArgumentCaptor.forClass(com.slack.api.methods.request.chat.ChatPostMessageRequest.class); verify(client).chatPostMessage(sent.capture());
        assertThat(sent.getValue().getChannel()).isEqualTo("DADMIN"); assertThat(sent.getValue().getText()).contains("관리자만");
    }
    @Test void modalStorageIsBoundedAndExpirationReleasesCapacity() throws Exception {
        // 방치 모달은 1000개 상한을 지키고 30분 만료 후 자리를 재사용한다.
        var sequence=new java.util.concurrent.atomic.AtomicInteger();
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenAnswer(call->{var response=new ViewsOpenResponse();response.setOk(true);response.setView(View.builder().id("V"+sequence.incrementAndGet()).build());return response;});
        for(int i=0;i<1001;i++) ui.open("trigger","U1"); verify(client,times(1000)).viewsOpen(any(ViewsOpenRequest.class));
        when(clock.instant()).thenReturn(Instant.EPOCH.plusSeconds(1800));ui.open("trigger","U1");verify(client,times(1001)).viewsOpen(any(ViewsOpenRequest.class));
    }
    @Test void realBoltSubmissionUsesActorAndSelectedOptionAfterAck() throws Exception {
        // 실제 핸들러는 ACK 후 state.selected_option을 읽고 위조 metadata의 사용자를 무시한다.
        ui.open("trigger","U1"); var app=mock(com.slack.api.bolt.App.class);ui.register(app);
        var handler=ArgumentCaptor.forClass(com.slack.api.bolt.handler.builtin.ViewSubmissionHandler.class);verify(app).viewSubmission(eq(SlackMemberRegistrationService.FORM),handler.capture());
        var request=mock(com.slack.api.bolt.request.builtin.ViewSubmissionRequest.class);var context=mock(com.slack.api.bolt.context.builtin.ViewSubmissionContext.class);
        var payload=new com.slack.api.app_backend.views.payload.ViewSubmissionPayload();var user=new com.slack.api.app_backend.views.payload.ViewSubmissionPayload.User();user.setId("U1");payload.setUser(user);
        var family=new com.slack.api.model.view.ViewState.Value();family.setType("plain_text_input");family.setValue("김");
        var given=new com.slack.api.model.view.ViewState.Value();given.setType("plain_text_input");given.setValue("민수");
        var team=new com.slack.api.model.view.ViewState.Value();team.setType("static_select");var option=new com.slack.api.model.view.ViewState.SelectedOption();option.setValue("개발");team.setSelectedOption(option);
        payload.setView(View.builder().id("V1").privateMetadata("UATTACK:99").state(com.slack.api.model.view.ViewState.builder().values(Map.of(
                "familyName",Map.of("value",family),"givenName",Map.of("value",given),"team",Map.of("value",team))).build()).build());
        when(request.getPayload()).thenReturn(payload);handler.getValue().apply(request,context);
        var order=inOrder(context,registrations);order.verify(context).ack();order.verify(registrations).submit("U1","김","민수","개발");
    }
    @Test void longTeamOptionKeepsValueButBoundsDisplayLabel() throws Exception {
        // 80자 팀 이름은 값으로 보존하고 Slack 선택지 텍스트만 75자로 제한한다.
        when(registrations.teams()).thenReturn(List.of("가".repeat(80)));ui.open("trigger","U1");
        var request=ArgumentCaptor.forClass(ViewsOpenRequest.class);verify(client).viewsOpen(request.capture());
        var option=com.google.gson.JsonParser.parseString(request.getValue().getViewAsString()).getAsJsonObject().getAsJsonArray("blocks")
                .get(2).getAsJsonObject().getAsJsonObject("element").getAsJsonArray("options").get(0).getAsJsonObject();
        assertThat(option.getAsJsonObject("text").get("text").getAsString()).hasSize(75);
        assertThat(option.get("value").getAsString()).hasSize(80);
    }
    @Test void providerFailureDoesNotExposeExceptionBody(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // Slack 예외 원문은 Home 안내와 로그 어디에도 출력하지 않는다.
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenThrow(new java.io.IOException("provider-secret-body"));
        ui.open("trigger","U1");verify(homes).publish(eq("U1"),argThat(text->!text.contains("provider-secret-body")));
        assertThat(output.getAll()).doesNotContain("provider-secret-body");
    }
    @Test void teamOptionsRespectSlackLimit() throws Exception {
        // 정적 팀 선택은 Slack 최대 100개만 표시하고 새 팀 입력은 유지한다.
        when(registrations.teams()).thenReturn(java.util.stream.IntStream.range(0,101).mapToObj(i->"팀"+i).toList()); ui.open("trigger","U1");
        var request=ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(request.capture());
        var json=com.google.gson.JsonParser.parseString(request.getValue().getViewAsString()).getAsJsonObject();
        assertThat(json.getAsJsonArray("blocks").get(2).getAsJsonObject().getAsJsonObject("element").getAsJsonArray("options")).hasSize(100);
    }
}
