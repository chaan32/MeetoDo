package com.meetodo.slack;

import com.google.gson.JsonParser;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.MemberManagementException;
import com.meetodo.member.MemberService;
import com.meetodo.member.dto.MemberRequest;
import com.meetodo.member.dto.MemberResponse;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.*;
import com.slack.api.methods.response.views.*;
import com.slack.api.model.view.View;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SlackMemberManagementTests {
    final MethodsClient client = mock(MethodsClient.class);
    final MemberService members = mock(MemberService.class);
    final AdminAuthorization admins = mock(AdminAuthorization.class);
    final Clock clock = mock(Clock.class);
    final SlackHomeService homes = mock(SlackHomeService.class);
    final MemberResponse member = new MemberResponse(7L, "남궁", "민수", "남궁민수", "백엔드팀", "U7", true);
    final MemberRequest valid = new MemberRequest("남궁", "민수", "백엔드팀", "U7");
    SlackMemberManagementService management;
    @BeforeEach void setup() throws Exception {
        when(clock.instant()).thenReturn(Instant.parse("2026-11-13T00:00:00Z"));
        when(members.findAll()).thenReturn(List.of(member));
        var opened = new ViewsOpenResponse(); opened.setOk(true); opened.setView(View.builder().id("DIRECTORY").build());
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenReturn(opened);
        var pushed = new ViewsPushResponse(); pushed.setOk(true); pushed.setView(View.builder().id("EDITOR").build());
        when(client.viewsPush(any(ViewsPushRequest.class))).thenReturn(pushed);
        management = new SlackMemberManagementService(client, members, admins, clock, homes);
    }
    void directory() { management.open("trigger", "UA"); }
    void create() { directory(); management.select("trigger", "DIRECTORY", "UA", null); }
    void edit() { directory(); management.select("trigger", "DIRECTORY", "UA", "7"); }

    @Test void directoryIsAdministratorOnly() throws Exception {
        // 환경변수 관리자가 아닌 요청은 목록 조회와 모달 열기 전에 차단한다.
        doThrow(new MemberManagementException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "관리자만 팀원을 관리할 수 있어요.")).when(admins).requireAdmin("UA");
        directory(); verifyNoInteractions(members); verify(client, never()).viewsOpen(any(ViewsOpenRequest.class));
    }
    @Test void directoryShowsExistingMembersAndNewMemberAction() throws Exception {
        // Slack 고유 ID와 팀을 함께 표시해 동명이인도 구별한다.
        directory(); var captured = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(captured.capture());
        assertThat(captured.getValue().getViewAsString()).contains("남궁민수", "백엔드팀", "U7", "static_select", "새 팀원 등록").doesNotContain("private_metadata");
    }
    @Test void directoryRespectsOneHundredOptionLimit() throws Exception {
        // Slack 정적 선택 메뉴의 100개 제한을 넘기지 않고 제한을 안내한다.
        when(members.findAll()).thenReturn(java.util.stream.LongStream.rangeClosed(1, 101).mapToObj(id -> new MemberResponse(id, "김", "이름", "김이름", "팀", "U" + id, true)).toList());
        directory(); var captured = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(captured.capture());
        var blocks = JsonParser.parseString(captured.getValue().getViewAsString()).getAsJsonObject().getAsJsonArray("blocks");
        assertThat(blocks.toString()).contains("100");
        var optionCounts = java.util.stream.StreamSupport.stream(blocks.spliterator(), false).filter(b -> b.getAsJsonObject().has("accessory")).map(b -> b.getAsJsonObject().getAsJsonObject("accessory").getAsJsonArray("options").size()).toList();
        assertThat(optionCounts).containsExactly(100);
    }
    @Test void memberWithoutSlackConnectionCanStillBeSelected() throws Exception {
        // 기존 데이터에 Slack 연결이 없어도 목록을 열고 실제 사용자를 새로 선택할 수 있다.
        when(members.findAll()).thenReturn(List.of(new MemberResponse(7L, "김", "민수", "김민수", "팀", null, true)));
        directory(); management.select("trigger", "DIRECTORY", "UA", "7");
        var captured = ArgumentCaptor.forClass(ViewsPushRequest.class); verify(client).viewsPush(captured.capture());
        assertThat(captured.getValue().getViewAsString()).contains("users_select").doesNotContain("initial_user");
    }
    @Test void createFormHasExplicitNameFieldsAndNativeSlackUserSelector() throws Exception {
        // 성과 이름을 직접 받고 Slack 이름 문자열 대신 users_select의 ID를 받는다.
        create(); var captured = ArgumentCaptor.forClass(ViewsPushRequest.class); verify(client).viewsPush(captured.capture());
        assertThat(captured.getValue().getViewAsString()).contains("familyName", "givenName", "team", "users_select", "\"max_length\":80").doesNotContain("private_metadata");
    }
    @Test void forgedDirectoryCannotOpenEditor() throws Exception {
        // 서버에서 연 적 없는 view ID로는 신규/수정 폼을 열지 못한다.
        management.select("trigger", "FORGED", "UA", "7"); verify(client, never()).viewsPush(any(ViewsPushRequest.class));
    }
    @Test void anotherActorCannotUseDirectory() throws Exception {
        // 관리자라도 다른 사람이 연 모달은 재사용하지 못한다.
        directory(); management.select("trigger", "DIRECTORY", "UB", "7"); verify(client, never()).viewsPush(any(ViewsPushRequest.class));
    }
    @Test void editorOpenRechecksAdministrator() throws Exception {
        // 디렉터리를 연 후 관리 권한이 없어지면 폼 열기도 거절한다.
        directory(); doThrow(new IllegalArgumentException()).when(admins).requireAdmin("UA");
        management.select("trigger", "DIRECTORY", "UA", "7"); verify(client, never()).viewsPush(any(ViewsPushRequest.class));
    }
    @Test void createUsesCommonMemberService() {
        // 신규 입력을 추측하거나 분리하지 않고 공통 서비스에 그대로 전달한다.
        create(); management.submit("EDITOR", "UA", valid); verify(members).create(valid, "UA"); verify(homes).publish(eq("UA"), contains("등록"));
    }
    @Test void editUsesServerStoredMemberId() throws Exception {
        // 수정 대상은 선택 때 서버가 저장하며 현재 DB 필드를 폼 초기값으로 쓴다.
        edit(); var captured = ArgumentCaptor.forClass(ViewsPushRequest.class); verify(client).viewsPush(captured.capture());
        assertThat(captured.getValue().getViewAsString()).contains("남궁", "민수", "백엔드팀", "initial_user", "U7", "confirm", "비활성화");
        management.submit("EDITOR", "UA", valid); verify(members).update(7L, valid, "UA");
    }
    @Test void revokedAdministratorCannotSubmit() {
        // 제출 순간의 권한을 재검사해 모달을 연 후의 권한 회수를 반영한다.
        create(); doThrow(new IllegalArgumentException()).when(admins).requireAdmin("UA"); management.submit("EDITOR", "UA", valid);
        verify(members, never()).create(any(), any());
    }
    @Test void forgedSubmissionCannotCreateMember() {
        // private_metadata나 콜백 이름만 위조한 제출에는 서버 문맥이 없다.
        management.submit("FORGED", "UA", valid); verify(members, never()).create(any(), any());
    }
    @Test void wrongActorCannotConsumeSubmission() {
        // 다른 사용자의 공격이 정상 관리자의 제출까지 소진시키지 않는다.
        create(); management.submit("EDITOR", "UB", valid); management.submit("EDITOR", "UA", valid); verify(members, times(1)).create(valid, "UA");
    }
    @Test void repeatedSubmissionMutatesOnce() {
        // 같은 view_submission 재전송은 멱등하게 한 번만 실행한다.
        create(); management.submit("EDITOR", "UA", valid); management.submit("EDITOR", "UA", valid); verify(members, times(1)).create(valid, "UA");
    }
    @Test void expiredModalCannotSubmit() {
        // 30분이 지난 서버 문맥으로는 팀원을 변경하지 못한다.
        create(); when(clock.instant()).thenReturn(Instant.parse("2026-11-13T00:30:00Z")); management.submit("EDITOR", "UA", valid);
        verify(members, never()).create(any(), any());
    }
    @Test void cancelledModalCannotSubmit() {
        // 닫힌 모달의 지연 제출도 변경을 실행하지 않는다.
        create(); management.close("EDITOR", "UA"); management.submit("EDITOR", "UA", valid); verify(members, never()).create(any(), any());
    }
    @Test void wrongActorCannotCancelModal() {
        // 다른 사용자에게 보낸 view_closed는 소유자의 문맥을 지우지 않는다.
        create(); management.close("EDITOR", "UB"); management.submit("EDITOR", "UA", valid); verify(members).create(valid, "UA");
    }
    @Test void blankFamilyNameIsRejected() {
        // Slack 필수 필드를 우회한 공백 성은 서버에서도 거절한다.
        create(); management.submit("EDITOR", "UA", new MemberRequest(" ", "민수", "팀", "U7")); verify(members, never()).create(any(), any());
    }
    @Test void blankGivenNameIsRejected() {
        // 이름 누락을 성이나 Slack 표시 이름으로 추측하지 않는다.
        create(); management.submit("EDITOR", "UA", new MemberRequest("김", "", "팀", "U7")); verify(members, never()).create(any(), any());
    }
    @Test void blankTeamIsRejected() {
        // 팀도 명시적으로 입력해야 한다.
        create(); management.submit("EDITOR", "UA", new MemberRequest("김", "민수", " ", "U7")); verify(members, never()).create(any(), any());
    }
    @Test void overlongFieldIsRejected() {
        // UI의 길이 제한을 우회한 81자 입력은 잘라 저장하지 않는다.
        create(); management.submit("EDITOR", "UA", new MemberRequest("가".repeat(81), "민수", "팀", "U7")); verify(members, never()).create(any(), any());
    }
    @Test void exactEightyCharacterLimitIsAccepted() {
        // 최대 길이 80자는 그대로 공통 검증 경계까지 전달한다.
        create(); var request = new MemberRequest("가".repeat(80), "나".repeat(80), "다".repeat(80), "W123"); management.submit("EDITOR", "UA", request); verify(members).create(request, "UA");
    }
    @Test void slackDisplayNameIsRejected() {
        // 사용자 선택 없이 표시 이름을 ID로 위조한 입력은 거절한다.
        create(); management.submit("EDITOR", "UA", new MemberRequest("김", "민수", "팀", "@민수")); verify(members, never()).create(any(), any());
    }
    @Test void absentRequestIsRejected() {
        // 잘못된 콜백 state 전체 누락도 변경을 실행하지 않는다.
        create(); management.submit("EDITOR", "UA", null); verify(members, never()).create(any(), any());
    }
    @Test void deactivationUsesStoredMemberAndRechecksAdmin() {
        // 확인 버튼을 눌러도 저장된 수정 대상과 현재 관리자 권한을 다시 사용한다.
        edit(); management.deactivate("EDITOR", "UA"); verify(members).deactivate(7L, "UA"); verify(admins, times(3)).requireAdmin("UA");
        management.submit("EDITOR", "UA", valid); verify(members, never()).update(any(), any(), any());
    }
    @Test void createModalCannotDeactivate() {
        // 신규 등록 문맥에는 비활성화할 ID가 없다.
        create(); management.deactivate("EDITOR", "UA"); verify(members, never()).deactivate(any(), any());
    }
    @Test void inactiveMemberIsLabelledWithoutReactivationControl() throws Exception {
        // 비활성 상태는 표시하지만 활성 전환 기능을 임의로 만들지 않는다.
        when(members.findAll()).thenReturn(List.of(new MemberResponse(7L, "김", "민수", "김민수", "팀", "U7", false)));
        directory(); var directory = ArgumentCaptor.forClass(ViewsOpenRequest.class); verify(client).viewsOpen(directory.capture());
        assertThat(directory.getValue().getViewAsString()).contains("비활성"); management.select("trigger", "DIRECTORY", "UA", "7");
        var editor = ArgumentCaptor.forClass(ViewsPushRequest.class); verify(client).viewsPush(editor.capture()); assertThat(editor.getValue().getViewAsString()).doesNotContain("meetodo_member_deactivate", "재활성화");
    }
    @Test void providerFailureOnlyPublishesSafePrivateNotice(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // Slack 예외 원문과 토큰은 Home 안내와 로그 모두에 노출하지 않는다.
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenThrow(new java.io.IOException("provider secret body")); directory();
        verify(homes).publish(eq("UA"), argThat(value -> !value.contains("provider secret body"))); assertThat(output.getAll()).doesNotContain("provider secret body");
    }
    @Test void uniqueConflictKeepsSafeDomainNotice() {
        // 비활성 팀원을 포함한 Slack ID 충돌 정책은 공통 서비스의 안전한 안내를 사용한다.
        create(); when(members.create(valid, "UA")).thenThrow(new MemberManagementException(HttpStatus.CONFLICT, "MEMBER_SLACK_USER_CONFLICT", "이미 다른 팀원에 연결된 Slack 사용자예요."));
        management.submit("EDITOR", "UA", valid); verify(homes).publish("UA", "이미 다른 팀원에 연결된 Slack 사용자예요.");
    }
    @Test void modalCacheIsBoundedAndExpiredEntriesReleaseCapacity() throws Exception {
        // 방치된 모달도 메모리 상한 1000건을 지키며 만료 후 새 요청이 자리를 재사용한다.
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenAnswer(call -> {
            var response = new ViewsOpenResponse(); response.setOk(true); response.setView(View.builder().id("V" + sequence.incrementAndGet()).build()); return response;
        });
        for (int index = 0; index < 1001; index++) directory();
        verify(client, times(1000)).viewsOpen(any(ViewsOpenRequest.class));
        when(clock.instant()).thenReturn(Instant.parse("2026-11-13T00:30:00Z")); directory();
        verify(client, times(1001)).viewsOpen(any(ViewsOpenRequest.class));
    }
    @Test void submissionHandlerAcknowledgesBeforeMutationAndUsesSelectedUser() throws Exception {
        // 실제 Bolt 핸들러는 ACK 이후에만 실행하고 표시 이름 대신 selected_user를 사용한다.
        create(); var app = mock(com.slack.api.bolt.App.class); management.register(app);
        var handler = ArgumentCaptor.forClass(com.slack.api.bolt.handler.builtin.ViewSubmissionHandler.class);
        verify(app).viewSubmission(eq(SlackMemberManagementService.FORM), handler.capture());
        var request = mock(com.slack.api.bolt.request.builtin.ViewSubmissionRequest.class);
        var context = mock(com.slack.api.bolt.context.builtin.ViewSubmissionContext.class);
        var payload = new com.slack.api.app_backend.views.payload.ViewSubmissionPayload();
        var user = new com.slack.api.app_backend.views.payload.ViewSubmissionPayload.User(); user.setId("UA"); payload.setUser(user);
        var userValue = state("users_select", "display name"); userValue.setSelectedUser("U7");
        payload.setView(View.builder().id("EDITOR").privateMetadata("forged-member-id").state(com.slack.api.model.view.ViewState.builder().values(java.util.Map.of(
                "familyName", java.util.Map.of("value", state("plain_text_input", "남궁")),
                "givenName", java.util.Map.of("value", state("plain_text_input", "민수")),
                "team", java.util.Map.of("value", state("plain_text_input", "백엔드팀")),
                "slackUserId", java.util.Map.of("value", userValue))).build()).build());
        when(request.getPayload()).thenReturn(payload);
        handler.getValue().apply(request, context);
        var ordered = inOrder(context, members); ordered.verify(context).ack(); ordered.verify(members).create(valid, "UA");
    }
    @Test void submissionHandlerRejectsSlackIdInPlainInput() throws Exception {
        // users_select 필드를 plain_text_input으로 위조하면 형식이 맞는 ID도 받지 않는다.
        create(); var app = mock(com.slack.api.bolt.App.class); management.register(app);
        var handler = ArgumentCaptor.forClass(com.slack.api.bolt.handler.builtin.ViewSubmissionHandler.class);
        verify(app).viewSubmission(eq(SlackMemberManagementService.FORM), handler.capture());
        var request = mock(com.slack.api.bolt.request.builtin.ViewSubmissionRequest.class);
        var context = mock(com.slack.api.bolt.context.builtin.ViewSubmissionContext.class);
        var payload = new com.slack.api.app_backend.views.payload.ViewSubmissionPayload();
        var user = new com.slack.api.app_backend.views.payload.ViewSubmissionPayload.User(); user.setId("UA"); payload.setUser(user);
        payload.setView(View.builder().id("EDITOR").state(com.slack.api.model.view.ViewState.builder().values(java.util.Map.of(
                "familyName", java.util.Map.of("value", state("plain_text_input", "남궁")),
                "givenName", java.util.Map.of("value", state("plain_text_input", "민수")),
                "team", java.util.Map.of("value", state("plain_text_input", "백엔드팀")),
                "slackUserId", java.util.Map.of("value", state("plain_text_input", "U7")))).build()).build());
        when(request.getPayload()).thenReturn(payload); handler.getValue().apply(request, context);
        verify(members, never()).create(any(), any());
    }
    private com.slack.api.model.view.ViewState.Value state(String type, String value) {
        var state = new com.slack.api.model.view.ViewState.Value(); state.setType(type); state.setValue(value); return state;
    }

}
