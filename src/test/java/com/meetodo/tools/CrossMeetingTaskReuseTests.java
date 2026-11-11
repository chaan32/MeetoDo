package com.meetodo.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingRepository;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.meeting.ToolCallLogRepository;
import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import com.meetodo.task.TaskMentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@ActiveProfiles("test")
class CrossMeetingTaskReuseTests {

    @Autowired MemberRepository members;
    @Autowired MeetingRepository meetings;
    @Autowired TaskRepository tasks;
    @Autowired ToolCallLogRepository logs;

    Member assignee;
    Meeting originalMeeting;
    Meeting repeatedMeeting;
    ToolExecutionService execution;
    TaskService taskService;
    TaskMentionService mentions;

    @BeforeEach
    void cleanAndPrepare() {
        tasks.deleteAll();
        logs.deleteAll();
        meetings.deleteAll();
        members.deleteAll();
        assignee = members.save(new Member("민수", "U-MINSU"));
        originalMeeting = meetings.save(Meeting.create("첫 회의", MeetingSource.API_TEXT));
        repeatedMeeting = meetings.save(Meeting.create("끝에 #만 붙은 회의", MeetingSource.API_TEXT));
        execution = new ToolExecutionService(logs, new ObjectMapper());
        mentions = mock(TaskMentionService.class);
        taskService = new TaskService(tasks, members, mentions);
    }

    // 검증: 다른 회의의 미완료 작업과 담당자·정규화 제목이 같으면 조회 결과에 EXISTING으로 포함한다.
    @Test
    void findTasksReturnsMatchingTodoFromAnotherMeetingAsExisting() {
        Task existing = tasks.save(Task.create("Deploy  Guide", assignee, null, originalMeeting));
        new FindMemberTool(members, execution).findMember("민수", repeatedMeeting);
        FindTasksTool tool = new FindTasksTool(members, tasks, execution, mentions);

        ToolResult result = tool.findTasks("민수", " deploy guide ", repeatedMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("EXISTING").contains(existing.getId().toString());
    }

    // 검증: 서버 측 createTask 중복 검사도 다른 회의의 TODO를 재사용하고 결과에 기존 작업을 표시한다.
    @Test
    void createTaskReusesMatchingTodoFromAnotherMeetingAsExisting() {
        Task existing = tasks.save(Task.create("Deploy  Guide", assignee, null, originalMeeting));
        CreateTaskTool tool = createTaskTool();

        ToolResult result = tool.createTask(" deploy guide ", "member:" + assignee.getId(), null, repeatedMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("EXISTING");
        assertThat(result.data()).contains("EXISTING").contains(existing.getId().toString());
        assertThat(tasks.count()).isEqualTo(1);
    }

    // 검증: 담당자 미정인 TODO도 다른 회의에서 같은 정규화 제목이면 재사용한다.
    @Test
    void createTaskReusesMatchingUnassignedTodoFromAnotherMeeting() {
        Task existing = tasks.save(Task.create("회의록  공유", null, null, originalMeeting));
        CreateTaskTool tool = createTaskTool();

        ToolResult result = tool.createTask("회의록 공유", "unassigned:NONE", null, repeatedMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("EXISTING").contains(existing.getId().toString());
        assertThat(tasks.count()).isEqualTo(1);
    }

    // 검증: 완료된 기존 작업은 전역 중복으로 보지 않고 새 회의 작업으로 등록한다.
    @Test
    void createTaskCreatesNewTaskWhenMatchingTaskIsDone() {
        Task done = Task.create("배포 설정 확인", assignee, null, originalMeeting);
        done.changeStatus(com.meetodo.task.TaskStatus.DONE);
        tasks.save(done);

        ToolResult result = createTaskTool().createTask("배포 설정 확인", "member:" + assignee.getId(), null, repeatedMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("CREATED");
        assertThat(tasks.count()).isEqualTo(2);
    }

    private CreateTaskTool createTaskTool() {
        new FindMemberTool(members, execution).findMember("민수", repeatedMeeting);
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new CreateTaskTool(members, taskService, mentions, execution, clock);
    }
}
