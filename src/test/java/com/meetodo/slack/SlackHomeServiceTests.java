package com.meetodo.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import com.meetodo.task.*;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsPublishRequest;
import com.slack.api.methods.response.views.ViewsPublishResponse;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SlackHomeServiceTests {
    final MethodsClient client = mock(MethodsClient.class);
    final MemberRepository members = mock(MemberRepository.class);
    final TaskRepository tasks = mock(TaskRepository.class);
    final TaskService service = mock(TaskService.class);
    final Member owner = member(1L, "U1"), other = member(2L, "U2");
    final Clock clock = Clock.fixed(Instant.parse("2026-11-12T15:30:00Z"), ZoneOffset.UTC);
    SlackHomeService home;
    @BeforeEach void setup() throws Exception {
        home = new SlackHomeService(client, members, tasks, service, new AdminAuthorization("UADMIN"), clock);
        when(members.findBySlackUserIdAndActiveTrue("U1")).thenReturn(Optional.of(owner));
        when(members.findBySlackUserIdAndActiveTrue("U2")).thenReturn(Optional.of(other));
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(any(), eq(TaskStatus.TODO))).thenReturn(List.of());
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of());
        ViewsPublishResponse response = new ViewsPublishResponse(); response.setOk(true);
        when(client.viewsPublish(any(ViewsPublishRequest.class))).thenReturn(response);
    }
    @Test void homeOnlyListsCurrentOwnerAndUnassignedTodo() throws Exception {
        // 저장소가 예상 밖 행을 반환해도 남의 업무·완료·삭제 항목을 Home에 노출하지 않는다.
        Task own = task(1, "내 일 <@U99>", owner, null), unassigned = task(2, "미정", null, null);
        Task done = task(3, "완료 비밀", owner, null); done.changeStatus(TaskStatus.DONE);
        Task deleted = task(4, "삭제 비밀", owner, null); deleted.softDelete();
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(owner, TaskStatus.TODO)).thenReturn(List.of(own, done, deleted, task(5, "남의 비밀", other, null)));
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(unassigned, task(6, "다른 비밀", other, null)));
        home.publish("U1"); String json = published().getViewAsString();
        assertThat(json).contains("내 일 &lt;@U99&gt;", "미정", SlackBotRenderer.COMPLETE_ACTION, SlackBotRenderer.TASK_MENU_ACTION)
                .doesNotContain("완료 비밀", "삭제 비밀", "남의 비밀", "다른 비밀", "meetodo_manage_members");
        assertThat(new ObjectMapper().readTree(json).path("type").asText()).isEqualTo("home");
    }
    @Test void dueBucketsUseSeoulDateAndSundayInclusive() throws Exception {
        // UTC 전날이어도 서울 오늘로 분류하고 이번 주 일요일과 다음 주 월요일을 구분한다.
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(owner, TaskStatus.TODO)).thenReturn(List.of(
                task(1,"날짜없음",owner,null),task(2,"다음주",owner,LocalDate.of(2026,11,16)),
                task(3,"일요일",owner,LocalDate.of(2026,11,15)), task(4,"오늘것",owner,LocalDate.of(2026,11,13)),
                task(5,"지연것",owner,LocalDate.of(2026,11,12))));
        home.publish("U1"); String json=published().getViewAsString();
        assertThat(json.indexOf("마감 지남")).isLessThan(json.indexOf("지연것"));
        assertThat(json.indexOf("지연것")).isLessThan(json.indexOf("오늘 마감"));
        assertThat(json.indexOf("오늘것")).isLessThan(json.indexOf("이번 주"));
        assertThat(json.indexOf("일요일")).isLessThan(json.indexOf("이후"));
        assertThat(json.indexOf("다음주")).isLessThan(json.indexOf("마감일 없음"));
    }
    @Test void unregisteredUserGetsRegistrationNoticeWithoutTasks() throws Exception {
        // 미등록 사용자는 등록 안내만 보며 업무 저장소를 조회하지 않는다.
        home.publish("UUNKNOWN"); assertThat(published().getViewAsString()).contains("팀원으로 등록되지 않았어요. 관리자에게 요청해 주세요.");
        verifyNoInteractions(tasks);
    }
    @Test void unregisteredAdminCanOpenMemberManagement() throws Exception {
        // 아직 팀원으로 등록하지 않은 관리자도 본인을 등록할 관리 버튼을 본다.
        home.publish("UADMIN"); assertThat(published().getViewAsString()).contains("meetodo_manage_members");
    }
    @Test void emptyTeamAdminGetsFirstMemberGuidanceAndManagementButton() throws Exception {
        // 활성 팀원이 없으면 미등록 관리자에게 첫 팀원 등록 안내와 관리 진입을 제공한다.
        when(members.existsByActiveTrue()).thenReturn(false);
        home.publish("UADMIN");
        assertThat(published().getViewAsString()).contains("먼저 팀원을 등록하세요", "👥 팀원 관리", "meetodo_manage_members")
                .doesNotContain("관리자에게 요청해 주세요", "미완료 할 일이 없어요");
        verifyNoInteractions(tasks);
    }
    @Test void emptyTeamNonAdminKeepsRegistrationNoticeWithoutManagementButton() throws Exception {
        // 빈 팀이어도 일반 사용자에게 관리자용 첫 등록 동작을 노출하지 않는다.
        when(members.existsByActiveTrue()).thenReturn(false);
        home.publish("UUNKNOWN");
        assertThat(published().getViewAsString()).contains("팀원으로 등록되지 않았어요. 관리자에게 요청해 주세요.")
                .doesNotContain("먼저 팀원을 등록하세요", "meetodo_manage_members");
        verifyNoInteractions(tasks);
    }
    @Test void nonemptyTeamUnregisteredAdminKeepsExistingRegistrationNotice() throws Exception {
        // 활성 팀원이 있으면 미등록 관리자도 기존 등록 안내와 관리 버튼을 함께 본다.
        when(members.existsByActiveTrue()).thenReturn(true);
        home.publish("UADMIN");
        assertThat(published().getViewAsString()).contains("팀원으로 등록되지 않았어요", "meetodo_manage_members")
                .doesNotContain("먼저 팀원을 등록하세요");
        verifyNoInteractions(tasks);
    }
    @Test void viewNeverExceedsHundredBlocksAndCountsOmittedTasks() throws Exception {
        // Home의 100블록 제한을 넘기지 않으며 생략한 업무 수를 API 안내와 함께 표시한다.
        List<Task> many = new ArrayList<>(); for(int i=1;i<=100;i++) many.add(task(i,"업무"+i,owner,null));
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(owner, TaskStatus.TODO)).thenReturn(many);
        home.publish("U1"); JsonNode blocks = new ObjectMapper().readTree(published().getViewAsString()).path("blocks");
        assertThat(blocks.size()).isLessThanOrEqualTo(100);
        int shown = 0; for(JsonNode block: blocks) if(block.path("type").asText().equals("actions") && block.path("block_id").asText().startsWith(SlackBotRenderer.ASSIGN_BLOCK_PREFIX)) shown++;
        assertThat(blocks.toString()).contains("외 " + (100-shown) + "건은 GET /api/tasks에서 확인");
    }
    @Test void refreshOnlyPublishesOpenedOwnerForOwnedTask() throws Exception {
        // 일반 변경은 Home을 연 현재 담당자만 갱신하고 미개방 사용자에게 전송하지 않는다.
        home.publish("U1"); home.publish("U2"); clearInvocations(client);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"수정됨",owner,null)));
        home.refreshTask(7L,false); assertThat(published().getUserId()).isEqualTo("U1");
    }
    @Test void assignmentRefreshesEveryOpenedHome() throws Exception {
        // 배정되면 공유 미정 목록에서 사라지므로 Home을 연 모든 사용자를 갱신한다.
        home.publish("U1"); home.publish("U2"); clearInvocations(client);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"배정됨",owner,null)));
        home.refreshTask(7L,true); verify(client,times(2)).viewsPublish(any(ViewsPublishRequest.class));
    }
    @Test void unassignedTaskRefreshesEveryOpenedHome() throws Exception {
        // 미정 업무의 변경도 모든 열린 Home의 공유 목록에 반영한다.
        home.publish("U1"); home.publish("U2"); clearInvocations(client);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"미정 수정",null,null)));
        home.refreshTask(7L,false); verify(client,times(2)).viewsPublish(any(ViewsPublishRequest.class));
    }
    @Test void refreshDoesNotIntroduceUsersWhoNeverOpenedHome() throws Exception {
        // Home을 열지 않은 담당자에게 이벤트만으로 새 화면을 발행하지 않는다.
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",owner,null)));
        home.refreshTask(7L,false); home.refreshAll(); verifyNoInteractions(client);
    }
    @Test void completeDelegatesToSharedTaskService() throws Exception {
        // 열린 Home의 자기 업무 완료는 공통 서비스의 DB 권한 검증을 사용한다.
        home.publish("U1"); when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",owner,null)));
        home.complete("U1","7"); verify(service).completeFromSlack(7L,"U1");
    }
    @Test void forgedOtherOwnerCannotReachSharedMutation() throws Exception {
        // 다른 담당자의 ID를 위조한 Home 동작은 공통 서비스 호출 전에 차단한다.
        home.publish("U1"); when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"비밀",other,null)));
        home.complete("U1","7"); verifyNoInteractions(service);
        assertThat(allPublishedJson()).doesNotContain("비밀");
    }
    @Test void unregisteredUserCannotMutateUnassignedTask() {
        // 미등록 사용자는 미정 업무 ID를 알아도 배정이나 완료를 요청할 수 없다.
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",null,null)));
        home.complete("UUNKNOWN","7"); verifyNoInteractions(service);
    }
    @Test void candidateAssignmentDelegatesExactIds() {
        // 후보 버튼에 포함된 업무·팀원 ID를 공통 배정 서비스에서 검증한다.
        home.publish("U1"); when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",null,null)));
        home.assignCandidate("U1","7:2"); verify(service).assign(7L,2L,"U1");
    }
    @Test void selectionAssignmentDelegatesExactIds() {
        // 후보 선택 메뉴도 block ID의 업무와 선택한 팀원을 동일 서비스로 전달한다.
        home.publish("U1"); when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",null,null)));
        home.assignSelection("U1",SlackBotRenderer.ASSIGN_BLOCK_PREFIX+"7","2"); verify(service).assign(7L,2L,"U1");
    }
    @Test void usersSelectionRequiresRegisteredActiveTarget() throws Exception {
        // 선택한 Slack 사용자가 미등록이면 요청자의 Home에만 안내하며 업무를 변경하지 않는다.
        home.publish("U1"); clearInvocations(client);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",null,null)));
        home.assignUser("U1",SlackBotRenderer.ASSIGN_BLOCK_PREFIX+"7","UMISSING");
        verifyNoInteractions(service); assertThat(published().getUserId()).isEqualTo("U1");
        assertThat(published().getViewAsString()).contains("등록된 팀원이 아니에요");
    }
    @Test void usersSelectionResolvesRegisteredTarget() {
        // Slack 사용자 선택은 DB의 활성 팀원 ID로 변환한 후 공통 서비스를 사용한다.
        home.publish("U1"); when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"업무",null,null)));
        home.assignUser("U1",SlackBotRenderer.ASSIGN_BLOCK_PREFIX+"7","U2"); verify(service).assign(7L,2L,"U1");
    }
    @Test void staleUserSelectionForAlreadyAssignedTaskOnlyRefreshesHome() throws Exception {
        // 이미 배정된 업무는 오래된 users_select 값이 미등록이어도 조회·재배정하지 않고 최신 Home을 보여 준다.
        home.publish("U1"); clearInvocations(client);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(task(7,"배정됨",owner,null)));
        home.assignUser("U1",SlackBotRenderer.ASSIGN_BLOCK_PREFIX+"7","UMISSING");
        verify(members,never()).findBySlackUserIdAndActiveTrue("UMISSING"); verifyNoInteractions(service);
        assertThat(published().getViewAsString()).doesNotContain("등록된 팀원이 아니에요");
    }
    @Test void staleUserSelectionForCompletedTaskOnlyRefreshesHome() throws Exception {
        // 이미 완료한 미정 업무도 잘못된 선택값을 무시하고 현재 Home만 갱신한다.
        home.publish("U1"); clearInvocations(client);
        Task completed=task(7,"완료됨",null,null); completed.changeStatus(TaskStatus.DONE);
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(completed));
        home.assignUser("U1",SlackBotRenderer.ASSIGN_BLOCK_PREFIX+"7","UMISSING");
        verify(members,never()).findBySlackUserIdAndActiveTrue("UMISSING"); verifyNoInteractions(service);
        assertThat(published().getViewAsString()).doesNotContain("등록된 팀원이 아니에요");
    }
    @Test void malformedActionCannotMutate() {
        // 잘못된 ID와 block ID는 예외 원문 없이 거절한다.
        home.publish("U1"); home.complete("U1","oops"); home.assignCandidate("U1","7:2:3"); home.assignSelection("U1","invalid","2");
        verifyNoInteractions(service);
    }
    @Test void deletedTaskCannotMutate() {
        // 이미 삭제된 행은 오래된 Home 버튼으로 다시 변경하지 못한다.
        home.publish("U1"); Task deleted=task(7,"삭제",owner,null); deleted.softDelete();
        when(tasks.findAllWithAssigneeByIdIn(List.of(7L))).thenReturn(List.of(deleted)); home.complete("U1","7"); verifyNoInteractions(service);
    }
    @Test void providerFailureDoesNotLeakSecret(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // 공급자 예외의 응답 본문과 비밀값은 로그에 기록하지 않는다.
        when(client.viewsPublish(any(ViewsPublishRequest.class))).thenThrow(new java.io.IOException("provider secret body"));
        assertThatCode(()->home.publish("U1")).doesNotThrowAnyException();
        assertThat(output.getAll()).contains("Slack Home publish failed: INTERNAL_ERROR").doesNotContain("provider secret body");
    }
    @Test void unopenedActorCannotMutateOrCreateHomeSubscription() throws Exception {
        // 열지 않은 Home에서 위조한 동작은 실행도 새 구독 등록도 하지 않는다.
        home.complete("U1","7"); home.refreshAll(); verifyNoInteractions(service,client);
    }
    @Test void deactivatedActorCannotUseOldHomeButtons() {
        // Home을 연 뒤 비활성화된 팀원은 오래된 버튼으로 미정 업무를 변경하지 못한다.
        home.publish("U1"); when(members.findBySlackUserIdAndActiveTrue("U1")).thenReturn(Optional.empty());
        home.assignCandidate("U1","7:2"); verifyNoInteractions(service);
    }
    @Test void invalidActorCannotPublishOrMutate() throws Exception {
        // 잘못된 Slack ID와 null 사용자도 API 호출이나 업무 변경 없이 거절한다.
        home.publish(null); home.publish("<@U1>"); home.complete(null,"7"); home.refreshAll();
        verifyNoInteractions(service,client);
    }
    @Test void sundayBelongsToTodayAndMondayToLaterBucket() throws Exception {
        // 일요일에는 이번 주 남은 날짜가 없으므로 월요일을 이후 구간으로 표시한다.
        home=new SlackHomeService(client,members,tasks,service,new AdminAuthorization(""),
                Clock.fixed(Instant.parse("2026-11-14T15:30:00Z"),ZoneOffset.UTC));
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(owner,TaskStatus.TODO)).thenReturn(List.of(
                task(1,"일요업무",owner,LocalDate.of(2026,11,15)),task(2,"월요업무",owner,LocalDate.of(2026,11,16))));
        home.publish("U1"); String json=published().getViewAsString();
        assertThat(json).contains("오늘 마감","이후").doesNotContain("이번 주");
        assertThat(json.indexOf("일요업무")).isLessThan(json.indexOf("이후"));
    }
    @Test void providerErrorCodeDoesNotExposeResponseBody(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        // 알 수 없는 공급자 오류값은 안전한 코드로 바꾸고 원문 응답을 로그에서 제외한다.
        ViewsPublishResponse response=new ViewsPublishResponse(); response.setOk(false); response.setError("provider secret body");
        when(client.viewsPublish(any(ViewsPublishRequest.class))).thenReturn(response); home.publish("U1");
        assertThat(output.getAll()).doesNotContain("provider secret body");
    }
    private ViewsPublishRequest published() throws Exception {
        ArgumentCaptor<ViewsPublishRequest> request=ArgumentCaptor.forClass(ViewsPublishRequest.class);
        verify(client,atLeastOnce()).viewsPublish(request.capture()); return request.getValue();
    }
    private String allPublishedJson() throws Exception {
        ArgumentCaptor<ViewsPublishRequest> request=ArgumentCaptor.forClass(ViewsPublishRequest.class);
        verify(client,atLeastOnce()).viewsPublish(request.capture()); return request.getAllValues().stream().map(ViewsPublishRequest::getViewAsString).reduce("",String::concat);
    }
    private static Member member(long id,String user) {
        Member member=new Member("홍","길동","개발",user); ReflectionTestUtils.setField(member,"id",id); return member;
    }
    private static Task task(long id,String title,Member member,LocalDate due) {
        Task task=Task.create(title,member,due); ReflectionTestUtils.setField(task,"id",id); ReflectionTestUtils.setField(task,"createdAt",Instant.EPOCH.plusSeconds(id)); return task;
    }
}
