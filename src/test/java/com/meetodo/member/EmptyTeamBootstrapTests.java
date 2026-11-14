package com.meetodo.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.meeting.ToolCallLog;
import com.meetodo.meeting.ToolCallLogRepository;
import com.meetodo.member.dto.MemberRequest;
import com.meetodo.slack.SlackHomeService;
import com.meetodo.slack.SlackMemberManagementService;
import com.meetodo.task.Task;
import com.meetodo.task.TaskMentionService;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import com.meetodo.tools.CreateTaskTool;
import com.meetodo.tools.FindMemberTool;
import com.meetodo.tools.ToolExecutionService;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.request.views.ViewsPushRequest;
import com.slack.api.methods.response.views.ViewsOpenResponse;
import com.slack.api.methods.response.views.ViewsPushResponse;
import com.slack.api.model.view.View;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmptyTeamBootstrapTests {
    private final MemberRepository members = mock(MemberRepository.class);

    private ApplicationContextRunner initializerContext(String profiles) {
        return new ApplicationContextRunner().withUserConfiguration(DevMemberDataInitializer.class)
                .withBean(MemberRepository.class, () -> members)
                .withPropertyValues("spring.profiles.active=" + profiles, "DEV_SLACK_USER_ID=");
    }

    @Test void dockerStartsWithoutSampleInitializerOrMemberWrites() {
        // Docker 빈 팀 시작에는 샘플 초기화 빈이 없고 키나 Slack 자격 증명도 필요하지 않다.
        initializerContext("docker").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ApplicationRunner.class);
            verifyNoInteractions(members);
        });
    }

    @Test void dockerOpenaiDoesNotSeedDevelopmentMembers() {
        // Docker에서 OpenAI를 선택해도 local 전용 샘플 초기화가 활성화되지 않는다.
        initializerContext("docker,openai").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ApplicationRunner.class);
            verifyNoInteractions(members);
        });
    }

    @Test void testProfileSuppressesInitializerEvenWithLocalEnabled() {
        // local을 함께 명시한 테스트도 개발 샘플을 자동 등록하지 않는다.
        initializerContext("local,test").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ApplicationRunner.class);
            verifyNoInteractions(members);
        });
    }

    @Test void localProfileStillProvidesExistingDevelopmentSamples() {
        // local 초기화만 기존 네 개발 샘플을 제공하여 개발 흐름을 유지한다.
        initializerContext("local").run(context -> {
            assertThat(context).hasSingleBean(ApplicationRunner.class);
            context.getBean(ApplicationRunner.class).run(null);
            verify(members, times(4)).save(any(Member.class));
        });
    }

    @Test void unregisteredConfiguredAdminCanRegisterSelfFromExistingModal() throws Exception {
        // 환경변수 관리자에게 본인 팀원 행이 없어도 기존 모달의 본인 등록을 허용한다.
        registerFromExistingModal("UADMIN");
    }

    @Test void unregisteredConfiguredAdminCanRegisterAnotherMemberFromExistingModal() throws Exception {
        // 환경변수 관리자는 본인 등록을 먼저 하지 않고도 다른 팀원을 등록할 수 있다.
        registerFromExistingModal("UMEMBER");
    }

    private void registerFromExistingModal(String targetSlackId) throws Exception {
        MethodsClient client = mock(MethodsClient.class);
        SlackHomeService homes = mock(SlackHomeService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        AdminAuthorization admins = new AdminAuthorization("UADMIN");
        var opened = new ViewsOpenResponse(); opened.setOk(true); opened.setView(View.builder().id("DIRECTORY").build());
        var pushed = new ViewsPushResponse(); pushed.setOk(true); pushed.setView(View.builder().id("EDITOR").build());
        when(client.viewsOpen(any(ViewsOpenRequest.class))).thenReturn(opened);
        when(client.viewsPush(any(ViewsPushRequest.class))).thenReturn(pushed);
        when(members.saveAndFlush(any(Member.class))).thenAnswer(call -> {
            Member member = call.getArgument(0); ReflectionTestUtils.setField(member, "id", 1L); return member;
        });
        var management = new SlackMemberManagementService(client, new MemberService(members, admins), admins, clock, homes);
        management.open("trigger", "UADMIN");
        management.select("trigger", "DIRECTORY", "UADMIN", null);
        management.submit("EDITOR", "UADMIN", new MemberRequest("김", "민수", "백엔드팀", targetSlackId));
        var saved = ArgumentCaptor.forClass(Member.class);
        verify(members).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSlackUserId()).isEqualTo(targetSlackId);
        assertThat(saved.getValue().isActive()).isTrue();
        verify(members, never()).findBySlackUserIdAndActiveTrue("UADMIN");
        verify(homes).publish("UADMIN", "팀원을 등록했어요.");
    }

    @Test void emptyTeamMeetingUsesRecordedNotFoundLookupAndCreatesUnassignedTask() throws Exception {
        // 빈 팀의 실제 도구 경로는 조회 실패 기록을 참조해 미정 업무와 경고를 저장한다.
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        TaskRepository tasks = mock(TaskRepository.class);
        TaskMentionService mentions = mock(TaskMentionService.class);
        ToolCallLogRepository logs = mock(ToolCallLogRepository.class);
        List<ToolCallLog> recorded = new ArrayList<>();
        Meeting meeting = Meeting.create("민수는 배포 문서를 정리한다.", MeetingSource.API_TEXT);
        ReflectionTestUtils.setField(meeting, "id", 1L);
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(1L)).thenAnswer(call -> List.copyOf(recorded));
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(1L))
                .thenAnswer(call -> recorded.isEmpty() ? Optional.empty() : Optional.of(recorded.getLast()));
        when(logs.save(any(ToolCallLog.class))).thenAnswer(call -> {
            ToolCallLog log = call.getArgument(0); recorded.add(log); return log;
        });
        when(tasks.save(any(Task.class))).thenAnswer(call -> {
            Task task = call.getArgument(0); ReflectionTestUtils.setField(task, "id", 7L); return task;
        });
        ToolExecutionService execution = new ToolExecutionService(logs, new ObjectMapper());
        var lookup = new FindMemberTool(members, execution).findMember("민수", meeting);
        assertThat(lookup.errorCode()).isEqualTo("MEMBER_NOT_FOUND");
        var create = new CreateTaskTool(members, new TaskService(tasks, members, mentions, clock), mentions, execution, clock)
                .createTask("배포 문서 정리", "unassigned:NOT_FOUND", null, lookup.lookupCallOrder(), meeting);

        assertThat(create.success()).isTrue();
        assertThat(create.data()).contains("담당자 미정", "등록되지 않은 담당자", "NOT_FOUND", "민수");
        var saved = ArgumentCaptor.forClass(Task.class);
        verify(tasks).save(saved.capture());
        assertThat(saved.getValue().getAssignee()).isNull();
        assertThat(saved.getValue().getUnresolvedAssignee().reason()).isEqualTo("NOT_FOUND");
        assertThat(saved.getValue().getUnresolvedAssignee().requestedAssigneeName()).isEqualTo("민수");
        assertThat(recorded).extracting(ToolCallLog::getCallOrder).containsExactly(1, 2);
        assertThat(recorded).extracting(ToolCallLog::getToolName).containsExactly("findMember", "createTask");
        verify(mentions).record(7L, 1L);
        verify(members, never()).save(any(Member.class));
    }
}
