package com.meetodo.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.meeting.MeetingStatus;
import com.meetodo.meeting.ToolCallLog;
import com.meetodo.meeting.ToolCallLogRepository;
import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import com.meetodo.task.TaskService;
import com.meetodo.task.TaskMentionService;
import com.meetodo.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingToolsTests {

    MemberRepository members;
    TaskRepository tasks;
    ToolCallLogRepository logs;
    TaskService taskService;
    TaskMentionService mentions;
    ToolExecutionService execution;
    FindMemberTool findMember;
    FindTasksTool findTasks;
    CreateTaskTool createTask;
    Meeting testMeeting;

    @BeforeEach
    void setUp() {
        members = mock(MemberRepository.class);
        tasks = mock(TaskRepository.class);
        logs = mock(ToolCallLogRepository.class);
        mentions = mock(TaskMentionService.class);
        taskService = new TaskService(tasks, members, mentions);
        execution = new ToolExecutionService(logs, new ObjectMapper());
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        testMeeting = Meeting.create("unit test meeting", MeetingSource.API_TEXT);
        findMember = new FindMemberTool(members, execution);
        findTasks = new FindTasksTool(members, tasks, execution, mentions);
        createTask = new CreateTaskTool(members, taskService, mentions, execution, clock);
    }

    // 검증: returnsMemberForExactNameAndNameWithWhitespace 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsMemberForExactNameAndNameWithWhitespace() {
        Member member = new Member("김", "민수", "미지정", "U-DEV-MINSU");
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(member));

        ToolResult result = findMember.findMember(" 민수 ", testMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("민수");
        verify(logs).save(any());
    }

    // 검증: returnsNotFoundResultInsteadOfThrowingForUnknownMember 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsNotFoundResultInsteadOfThrowingForUnknownMember() {
        when(members.findAllByGivenNameOrderByIdAsc("없는 사람")).thenReturn(List.of());

        ToolResult result = findMember.findMember("없는 사람", testMeeting);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MEMBER_NOT_FOUND");
    }

    // 검증: returnsInputErrorForBlankOrNullMemberName 시나리오의 기대 동작을 확인한다.
    @Test
    void returnsInputErrorForBlankOrNullMemberName() {
        assertThat(findMember.findMember(" ", testMeeting).errorCode()).isEqualTo("INVALID_MEMBER_NAME");
        assertThat(findMember.findMember(null, testMeeting).errorCode()).isEqualTo("INVALID_MEMBER_NAME");
    }

    // 검증: 동명이인 조회는 임의의 첫 항목 대신 모든 후보 ID와 팀을 반환한다.
    @Test
    void returnsAllCandidatesWhenMemberNameIsAmbiguous() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "백엔드팀", "U-BACKEND"), new Member("이", "지영", "디자인팀", "U-DESIGN")));

        ToolResult result = findMember.findMember("지영", testMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("AMBIGUOUS");
        assertThat(result.data()).contains("백엔드팀", "디자인팀", "id");
    }

    // 검증: findsTasksByExactAssigneeAndTitleIncludingUnassigned 시나리오의 기대 동작을 확인한다.
    @Test
    void findsTasksByExactAssigneeAndTitleIncludingUnassigned() {
        ReflectionTestUtils.setField(testMeeting, "id", 31L);
        Member member = new Member("김", "민수", "미지정", "U-DEV-MINSU");
        ReflectionTestUtils.setField(member, "id", 1L);
        when(members.findById(1L)).thenReturn(Optional.of(member));
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(31L)).thenReturn(List.of(ToolCallLog.success(testMeeting,
                "findMember", "{}", "{\"data\":\"[{\\\"id\\\":1}]\"}", 0, 1)));
        Task exact = Task.create("설계서 작성", member, null);
        Task unassigned = Task.create("설계서 작성", null, null);
        Task otherTitle = Task.create("다른 제목", member, null);
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(member));
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO))
                .thenReturn(List.of(exact, otherTitle));
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO))
                .thenReturn(List.of(unassigned));

        assertThat(findTasks.findTasks("민수", "설계서 작성", testMeeting).data()).contains("설계서 작성");
        assertThat(findTasks.findTasks(null, "설계서 작성", testMeeting).data()).contains("설계서 작성");
        assertThat(findTasks.findTasks("민수", "다른 제목", testMeeting).data()).doesNotContain("설계서 작성");
        verify(tasks, org.mockito.Mockito.times(2))
                .findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO);
        verify(tasks).findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO);
    }

    // 검증: createsTaskForMeetingOrLeavesUnknownAssigneeUnassigned 시나리오의 기대 동작을 확인한다.
    @Test
    void createsTaskForMeetingOrLeavesUnknownAssigneeUnassigned() {
        Meeting meeting = Meeting.create("private meeting raw text", MeetingSource.API_TEXT);
        ReflectionTestUtils.setField(meeting, "id", 42L);
        Member member = new Member("김", "민수", "미지정", "U-DEV-MINSU");
        ReflectionTestUtils.setField(member, "id", 1L);
        when(members.findById(1L)).thenReturn(Optional.of(member));
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(42L)).thenReturn(List.of(ToolCallLog.success(meeting,
                "findMember", "{}", "{\"data\":\"[{\\\"id\\\":1}]\"}", 0, 1)));
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        ToolResult assigned = createTask.createTask("회의 준비", "member:1", "2026-11-20", meeting);
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(42L)).thenReturn(List.of(ToolCallLog.failure(meeting,
                "findMember", "{\"name\":\"준호\"}", "{\"success\":false,\"errorCode\":\"MEMBER_NOT_FOUND\"}", "MEMBER_NOT_FOUND", "없음", 0, 2)));
        ToolResult unknownAssignee = createTask.createTask("자료 취합", "unassigned:NOT_FOUND", null, 2, meeting);

        assertThat(assigned.success()).isTrue();
        assertThat(unknownAssignee.success()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(tasks, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(task -> assertThat(task.getMeetingId()).isEqualTo(42L));
        assertThat(captor.getAllValues().get(1).getAssignee()).isNull();
    }

    // 검증: rejectsInvalidTitleDatesAndPastDatesWithoutSaving 시나리오의 기대 동작을 확인한다.
    @Test
    void rejectsInvalidTitleDatesAndPastDatesWithoutSaving() {
        Meeting meeting = Meeting.create("meeting", MeetingSource.API_TEXT);

        assertThat(createTask.createTask("  ", null, null, meeting).errorCode()).isEqualTo("INVALID_TITLE");
        assertThat(createTask.createTask("x".repeat(201), null, null, meeting).errorCode()).isEqualTo("TITLE_TOO_LONG");
        assertThat(createTask.createTask("작업", null, "2026/13/45", meeting).errorCode()).isEqualTo("INVALID_DUE_DATE");
        assertThat(createTask.createTask("작업", null, "다음주", meeting).errorCode()).isEqualTo("INVALID_DUE_DATE");
        assertThat(createTask.createTask("작업", null, "2026-11-10", meeting).errorCode()).isEqualTo("PAST_DUE_DATE");

        verify(tasks, never()).save(any(Task.class));
    }

    // 검증: leavesDueDateEmptyWhenOmitted 시나리오의 기대 동작을 확인한다.
    @Test
    void leavesDueDateEmptyWhenOmitted() {
        Meeting meeting = Meeting.create("meeting", MeetingSource.API_TEXT);
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        ToolResult result = createTask.createTask("기한 없는 작업", "unassigned:NONE", null, meeting);

        assertThat(result.success()).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(tasks).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isNull();
    }

    // 검증: 후보 목록에 없는 담당자 ID나 이름만으로 고른 동명이인은 저장하지 않는다.
    @Test
    void rejectsNonCandidateIdsAndNameOnlyAmbiguousAssignments() {
        when(members.findById(999L)).thenReturn(Optional.empty());
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "백엔드팀", "U-BACKEND"), new Member("이", "지영", "디자인팀", "U-DESIGN")));

        assertThat(createTask.createTask("API 확인", "member:999", null, testMeeting).errorCode())
                .isEqualTo("INVALID_ASSIGNEE");
        assertThat(createTask.createTask("화면 확인", "지영", null, testMeeting).errorCode())
                .isEqualTo("INVALID_ASSIGNEE");
        verify(tasks, never()).save(any(Task.class));
    }

    // 검증: 동명이인을 확정하지 못해 미정으로 등록하면 확인 필요 경고를 도구 결과에 남긴다.
    @Test
    void registersUnassignedTaskWithAmbiguousMemberWarning() throws Exception {
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        var mapper = new ObjectMapper();
        String data = mapper.writeValueAsString(List.of(Map.of("id", 2, "name", "지영", "team", "백엔드팀"),
                Map.of("id", 3, "name", "지영", "team", "디자인팀")));
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(testMeeting.getId())).thenReturn(List.of(ToolCallLog.success(testMeeting,
                "findMember", "{\"name\":\"지영\"}", mapper.writeValueAsString(ToolResult.success("AMBIGUOUS", data)), 0, 1)));
        ToolResult result = createTask.createTask("회의실 예약", "unassigned:AMBIGUOUS", null, 1, testMeeting);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("동명이인 확인 필요");
        assertThat(result.data()).contains("동명이인 확인 필요");
    }

    // 검증: afterRetryingTheSamePastDateRegistersWithoutDueDateAndReturnsWarning 시나리오의 기대 동작을 확인한다.
    @Test
    void afterRetryingTheSamePastDateRegistersWithoutDueDateAndReturnsWarning() {
        Meeting meeting = Meeting.create("meeting", MeetingSource.API_TEXT);
        org.springframework.test.util.ReflectionTestUtils.setField(meeting, "id", 61L);
        String arguments = "{\"title\":\"기한 확인\",\"assignee\":\"unassigned:NONE\",\"dueDate\":\"2026-11-10\"}";
        ToolCallLog previousFailure = ToolCallLog.failure(meeting, "createTask", arguments,
                "PAST_DUE_DATE", "past date", 1, 1);
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(61L)).thenReturn(List.of(previousFailure));
        when(members.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(new Member("김", "민수", "미지정", "U-DEV-MINSU")));
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        ToolResult result = createTask.createTask("기한 확인", "unassigned:NONE", "2026-11-10", meeting);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("마감일 없이").contains("경고");
        var captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(tasks).save(captor.capture());
        assertThat(captor.getValue().getDueDate()).isNull();
    }

    // 검증: 등록되지 않은 팀 조회는 같은 이름의 후보를 안내하고 자동 선택하지 않는다.
    @Test
    void unknownTeamReturnsNotFoundWithCandidates() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(new Member("이", "지영", "백엔드팀", "U-JIYOUNG")));
        ToolResult result = findMember.findMember("지영", "없는팀", Meeting.create("없는팀 지영 담당", MeetingSource.API_TEXT));
        assertThat(result.errorCode()).isEqualTo("MEMBER_TEAM_NOT_FOUND");
        assertThat(result.data()).contains("백엔드팀");
    }

    // 검증: 팀 앞뒤 공백은 제거하고 정확히 일치하는 팀원만 반환한다.
    @Test
    void trimsTeamWhitespaceAndReturnsOnlyExactTeamCandidate() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "백엔드팀", "U-BACKEND"), new Member("이", "지영", "디자인팀", "U-DESIGN")));
        ToolResult result = findMember.findMember(" 지영 ", " 백엔드팀 ", Meeting.create("백엔드팀 지영 담당", MeetingSource.API_TEXT));
        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("백엔드팀").doesNotContain("디자인팀");
    }

    // 검증: 누락된 assignee는 명시적인 미정 결정으로 취급하지 않는다.
    @Test
    void rejectsMissingAssigneeToken() { assertInvalidToken(null); }

    // 검증: 빈 문자열 assignee는 저장하지 않는다.
    @Test
    void rejectsBlankAssigneeToken() { assertInvalidToken(""); }

    // 검증: 허용하지 않은 미정 사유는 저장하지 않는다.
    @Test
    void rejectsUnknownUnassignedReason() { assertInvalidToken("unassigned:OTHER"); }

    // 검증: 0인 담당자 ID는 허용하지 않는다.
    @Test
    void rejectsZeroMemberId() { assertInvalidToken("member:0"); }

    // 검증: Long 범위를 초과하는 담당자 ID도 일반화된 입력 오류로 반환한다.
    @Test
    void rejectsOverflowingMemberId() { assertInvalidToken("member:999999999999999999999999"); }

    // 검증: 한 문자열에 배정과 미정을 동시에 담으면 거절한다.
    @Test
    void rejectsCombinedAssignedAndUnassignedToken() { assertInvalidToken("member:2,unassigned:AMBIGUOUS"); }

    // 검증: 실제 존재해도 현재 회의에서 조회된 후보가 아닌 ID는 거절한다.
    @Test
    void rejectsExistingMemberNotReturnedByLookup() {
        Member member = new Member("김", "민수", "미지정", "U-MINSU");
        ReflectionTestUtils.setField(member, "id", 2L);
        when(members.findById(2L)).thenReturn(Optional.of(member));
        assertThat(createTask.createTask("API 확인", "member:2", null, testMeeting).errorCode()).isEqualTo("INVALID_ASSIGNEE");
        verify(tasks, never()).save(any(Task.class));
    }

    // 검증: 배정 ID가 있는 조회에 미정 사유를 함께 보내면 거절한다.
    @Test
    void rejectsAssignedLookupWithUnassignedReason() {
        assertThat(findTasks.findTasks(2L, "지영", "API 수정", "AMBIGUOUS", testMeeting).errorCode())
                .isEqualTo("INVALID_ASSIGNEE");
    }

    // 검증: 조회에서도 허용하지 않은 미정 사유는 거절한다.
    @Test
    void rejectsUnknownUnassignedLookupReason() {
        assertThat(findTasks.findTasks(null, "", "API 수정", "OTHER", testMeeting).errorCode())
                .isEqualTo("INVALID_ASSIGNEE");
    }

    // 검증: 실패 결과도 후보 데이터를 보존하되 비밀값·원문을 마스킹하고 최대 길이로 제한한다.
    @Test
    void persistsFailedResultAfterMaskingAndTruncation() {
        String rawText = "FULL_PRIVATE_MEETING_TEXT";
        Meeting meeting = Meeting.create(rawText, MeetingSource.API_TEXT);
        String key = "sk-test-abcdefghijklmnopqrstuvwxyz123456";
        execution.execute(meeting, "findMember", Map.of("name", "해찬"), () -> new ToolResult(false,
                "MEMBER_TEAM_NOT_FOUND", "팀 불일치",
                "후보 백엔드팀 " + key + " Authorization: Bearer secret-token " + rawText + " " + "x".repeat(5000)));
        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        ToolCallLog stored = captor.getValue();
        assertThat(stored.getResult()).isNotNull().contains("백엔드팀", "[REDACTED]")
                .doesNotContain(key, "secret-token", rawText).hasSize(ToolCallLog.MAX_RESULT_LENGTH);
        assertThat(stored.getSuccess()).isFalse();
        assertThat(stored.getErrorCode()).isEqualTo("MEMBER_TEAM_NOT_FOUND");
    }

    // 검증: 빈 team은 팀 생략과 달리 입력 오류다.
    @Test
    void rejectsEmptyTeamInput() {
        assertThat(findMember.findMember("해찬", "", testMeeting).errorCode()).isEqualTo("INVALID_MEMBER_TEAM");
    }

    // 검증: 공백뿐인 team도 입력 오류다.
    @Test
    void rejectsWhitespaceOnlyTeamInput() {
        assertThat(findMember.findMember("해찬", "   ", testMeeting).errorCode()).isEqualTo("INVALID_MEMBER_TEAM");
    }

    // 검증: 실패한 findMember가 모델에 반환한 후보와 로그의 후보는 마스킹 대상이 없으면 일치한다.
    @Test
    void recordsFailureCandidatesReturnedToModel() throws Exception {
        when(members.findAllByGivenNameOrderByIdAsc("해찬")).thenReturn(List.of(new Member("전", "해찬", "백엔드팀", "U-HAECHAN")));
        ToolResult returned = findMember.findMember("해찬", "프론트엔드팀", Meeting.create("프론트엔드팀 해찬 담당", MeetingSource.API_TEXT));
        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        assertThat(new ObjectMapper().readTree(captor.getValue().getResult()).path("data").asText())
                .isEqualTo(returned.data()).contains("백엔드팀");
    }

    // 검증: 모델에 보여준 다중 후보 목록만으로 배정 권한을 얻을 수 없다.
    @Test
    void ambiguousCandidateInformationDoesNotAuthorizeAssignment() {
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(testMeeting.getId())).thenReturn(List.of(
                ToolCallLog.success(testMeeting, "findMember", "{}", "{\"data\":\"[{\\\"id\\\":2},{\\\"id\\\":3}]\"}", 0, 1)));
        assertThat(execution.isMemberAuthorized(testMeeting, 2L)).isFalse();
    }

    // 검증: 팀 문자열의 대소문자·내부 공백·접미사 차이는 회의록과 등록 팀에 동일하게 적용한다.
    @Test
    void normalizesTeamCaseWhitespaceAndSuffix() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(new Member("이", "지영", "BACKEND팀", "U-BACKEND")));
        Meeting meeting = Meeting.create("backend 지영은 API 수정", MeetingSource.API_TEXT);
        assertThat(findMember.findMember("지영", " B A C K E N D 팀 ", meeting).success()).isTrue();
    }

    // 검증: 팀 접미사만 있는 입력은 정규화 후 빈 값이므로 모든 회의록에 일치시키지 않는다.
    @Test
    void rejectsTeamSuffixWithoutName() {
        assertThat(findMember.findMember("지영", "팀", testMeeting).errorCode()).isEqualTo("INVALID_MEMBER_TEAM");
    }

    // 알려진 한계: BE팀 같은 약칭을 백엔드팀으로 확장하지 않는다.
    @Test
    void knownLimitationDoesNotExpandBeTeamAbbreviation() {
        Meeting meeting = Meeting.create("BE팀 지영은 API 수정", MeetingSource.API_TEXT);
        assertThat(findMember.findMember("지영", "백엔드팀", meeting).errorCode()).isEqualTo("TEAM_NOT_IN_TRANSCRIPT");
    }

    // 검증: 후보 팀 언급이 없으면 미정 등록을 직접 안내하고 후보 정보는 유지한다.
    @Test
    void ambiguousWithoutMentionedCandidateTeamsGuidesUnassigned() {
        stubAmbiguousJiyoung();
        ToolResult result = findMember.findMember("지영", Meeting.create("지영은 회의실 예약을 확인한다.", MeetingSource.API_TEXT));
        assertThat(result.message()).isEqualTo("AMBIGUOUS: 회의록에서 후보 팀(백엔드팀, 디자인팀)이 언급되지 않았습니다. unassigned:AMBIGUOUS로 등록하세요.");
        assertThat(result.success()).isTrue();
        assertThat(result.data()).contains("백엔드팀", "디자인팀");
    }

    // 검증: 후보 팀 하나만 언급되면 그 팀의 명시적 재조회를 안내하되 다중 후보 정보는 유지한다.
    @Test
    void ambiguousWithOneMentionedCandidateTeamGuidesTeamLookup() {
        stubAmbiguousJiyoung();
        ToolResult result = findMember.findMember("지영", Meeting.create("백엔드 지영은 API를 수정한다.", MeetingSource.API_TEXT));
        assertThat(result.message()).isEqualTo("AMBIGUOUS: 회의록에 백엔드팀이 언급되어 있습니다. team=백엔드팀으로 재조회하세요.");
        assertThat(result.data()).contains("백엔드팀", "디자인팀");
    }

    // 검증: 여러 후보 팀이 등장하면 특정 팀을 선택하도록 유도하지 않는다.
    @Test
    void ambiguousWithMultipleMentionedCandidateTeamsGivesNeutralGuidance() {
        stubAmbiguousJiyoung();
        ToolResult result = findMember.findMember("지영", Meeting.create("백엔드팀과 디자인팀 합동 회의. 지영 담당 확인", MeetingSource.API_TEXT));
        assertThat(result.message()).isEqualTo("AMBIGUOUS: 회의록에 여러 후보 팀이 언급되어 있습니다. 문맥으로 확실히 구분할 수 없으면 unassigned:AMBIGUOUS로 등록하세요.");
    }

    // 검증: 정규화 후 같은 팀은 후보 인원 수와 무관하게 한 팀으로 계산한다.
    @Test
    void ambiguousCountsDistinctNormalizedTeamsRatherThanMembers() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "BACKEND팀", "U-1"), new Member("이", "지영", "back end", "U-2"),
                new Member("이", "지영", "디자인팀", "U-3")));
        ToolResult result = findMember.findMember("지영", Meeting.create("backend 지영은 작업한다.", MeetingSource.API_TEXT));
        assertThat(result.message()).isEqualTo("AMBIGUOUS: 회의록에 BACKEND팀이 언급되어 있습니다. team=BACKEND팀으로 재조회하세요.");
        assertThat(result.data()).contains("U-1", "U-2", "U-3");
    }

    // 검증: 팀까지 지정했는데 동명이인이 남으면 반복 재조회 대신 기존 확정 불가 안내를 유지한다.
    @Test
    void explicitTeamStillAmbiguousKeepsSameTeamWarning() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "백엔드팀", "U-1"), new Member("이", "지영", "백엔드팀", "U-2")));
        ToolResult result = findMember.findMember("지영", "백엔드팀", Meeting.create("백엔드팀 지영 담당", MeetingSource.API_TEXT));
        assertThat(result.message()).contains("같은 팀에서도 여러 명이면 확정하지 마세요");
    }

    private void stubAmbiguousJiyoung() {
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(
                new Member("이", "지영", "백엔드팀", "U-BACKEND"), new Member("이", "지영", "디자인팀", "U-DESIGN")));
    }

    // 검증: 도구 인자·결과에 섞인 Slack/Discord 웹훅 자격 증명도 기록 전에 마스킹한다.
    @Test
    void redactsWebhookUrlsFromToolArgumentsAndResults() {
        String slack = "https://hooks.slack.com/services/SECRET/WEBHOOK/TOKEN";
        String discord = "https://discord.com/api/webhooks/123/SECRET-TOKEN";
        execution.execute(testMeeting, "test", Map.of("url", slack), () -> ToolResult.success("ok", discord));
        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getArguments()).doesNotContain(slack, "SECRET").contains("[REDACTED]");
        assertThat(captor.getValue().getResult()).doesNotContain(discord, "SECRET-TOKEN").contains("[REDACTED]");
    }

    private void assertInvalidToken(String token) {
        ToolResult result = createTask.createTask("API 확인", token, null, testMeeting);
        assertThat(result.errorCode()).isEqualTo("INVALID_ASSIGNEE");
        assertThat(result.message()).contains("배정과 미정 중 하나만 선택");
        verify(tasks, never()).save(any(Task.class));
    }

    // 검증: recordsSanitizedArgumentsAndUnexpectedFailuresWithoutThrowing 시나리오의 기대 동작을 확인한다.
    @Test
    void recordsSanitizedArgumentsAndUnexpectedFailuresWithoutThrowing() {
        Meeting meeting = Meeting.create("FULL_PRIVATE_MEETING_TEXT", MeetingSource.API_TEXT);
        ToolResult result = execution.execute(meeting, "testTool",
                "{\"title\":\"sk-test-abcdefghijklmnopqrstuvwxyz\",\"authorization\":\"Bearer sensitive-token\"}", () -> {
                    throw new IllegalStateException("internal secret");
                });

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        var captor = org.mockito.ArgumentCaptor.forClass(com.meetodo.meeting.ToolCallLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getArguments()).doesNotContain("sk-test-");
        assertThat(captor.getValue().getArguments()).doesNotContain("sensitive-token");
        assertThat(captor.getValue().getArguments()).doesNotContain("FULL_PRIVATE_MEETING_TEXT");
        assertThat(captor.getValue().getSuccess()).isFalse();
        assertThat(captor.getValue().getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    // 검증: recordsSuccessfulAndValidationFailureCallsWithTheirOutcomes 시나리오의 기대 동작을 확인한다.
    @Test
    void recordsSuccessfulAndValidationFailureCallsWithTheirOutcomes() {
        Meeting meeting = Meeting.create("raw meeting notes", MeetingSource.API_TEXT);
        execution.execute(meeting, "ok", Map.of("title", "간단 작업"),
                () -> ToolResult.success("완료", "작업이 등록되었습니다."));
        execution.execute(meeting, "invalid", Map.of("title", ""),
                () -> ToolResult.error("INVALID_TITLE", "제목을 입력하세요."));

        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getSuccess()).isTrue();
        assertThat(captor.getAllValues().get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(captor.getAllValues().get(1).getSuccess()).isFalse();
        assertThat(captor.getAllValues().get(1).getErrorCode()).isEqualTo("INVALID_TITLE");
    }

    // 검증: assignsTheNextCallOrderAfterThePreviousLog 시나리오의 기대 동작을 확인한다.
    @Test
    void assignsTheNextCallOrderAfterThePreviousLog() {
        Meeting meeting = Meeting.create("notes", MeetingSource.API_TEXT);
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(meeting.getId()))
                .thenReturn(Optional.of(ToolCallLog.success(meeting, "first", "{}", "{}", 1, 4)));

        execution.execute(meeting, "second", Map.of(), () -> ToolResult.success("ok", "{}"));

        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getCallOrder()).isEqualTo(5);
    }

    // 검증: truncatesLongResultsAndRedactsSecretsAndTheFullMeetingText 시나리오의 기대 동작을 확인한다.
    @Test
    void truncatesLongResultsAndRedactsSecretsAndTheFullMeetingText() {
        String rawText = "CONFIDENTIAL_FULL_MEETING_TEXT";
        Meeting meeting = Meeting.create(rawText, MeetingSource.API_TEXT);
        String key = "sk-test-abcdefghijklmnopqrstuvwxyz123456";
        execution.execute(meeting, "safe", Map.of("meetingText", rawText),
                () -> ToolResult.success("ok", "result " + key + " " + rawText + " " + "x".repeat(5000)));

        var captor = org.mockito.ArgumentCaptor.forClass(ToolCallLog.class);
        verify(logs).save(captor.capture());
        ToolCallLog log = captor.getValue();
        assertThat(log.getArguments()).doesNotContain(rawText);
        assertThat(log.getResult()).doesNotContain(key).doesNotContain(rawText);
        assertThat(log.getResult()).hasSize(4000);
    }
}
