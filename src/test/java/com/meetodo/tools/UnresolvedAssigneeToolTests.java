package com.meetodo.tools;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.*;
import com.meetodo.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class UnresolvedAssigneeToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ToolCallLog> stored = new ArrayList<>();
    private MemberRepository members;
    private TaskRepository tasks;
    private ToolExecutionService execution;
    private FindMemberTool find;
    private CreateTaskTool create;
    private Meeting meeting;

    @BeforeEach
    void setup() {
        members = mock(MemberRepository.class);
        tasks = mock(TaskRepository.class);
        var logs = mock(ToolCallLogRepository.class);
        when(logs.save(any())).thenAnswer(call -> { ToolCallLog log = call.getArgument(0); stored.add(log); return log; });
        when(logs.findAllByMeetingIdOrderByCallOrderAsc(anyLong())).thenAnswer(call -> stored.stream()
                .filter(log -> log.getMeeting().getId().equals(call.getArgument(0))).toList());
        when(logs.findTopByMeeting_IdOrderByCallOrderDesc(anyLong())).thenAnswer(call -> stored.stream()
                .filter(log -> log.getMeeting().getId().equals(call.getArgument(0))).reduce((a, b) -> b));
        when(tasks.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        execution = new ToolExecutionService(logs, mapper);
        var mentions = mock(TaskMentionService.class);
        find = new FindMemberTool(members, execution);
        create = new CreateTaskTool(members, new TaskService(tasks, members, mentions, java.time.Clock.systemUTC()), mentions,
                execution, Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneId.of("Asia/Seoul")));
        meeting = meeting(1L, "프론트엔드팀 해찬과 지영 담당 확인");
    }

    // 검증: findMember가 반환하는 참조 번호를 모델과 저장 로그가 함께 사용한다.
    @Test
    void findMemberReturnsLookupCallOrder() throws Exception {
        var result = find.findMember("없는이름", meeting);
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).path("lookupCallOrder").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(stored.getFirst().getResult())).isEqualTo(mapper.valueToTree(result));
    }

    // 검증: 동명이인 조회의 서버 후보와 이름을 새 Task에 보존한다.
    @Test
    void storesAmbiguousLookupSnapshot() {
        ambiguous();
        var lookup = find.findMember("지영", meeting);
        var result = create.createTask("확인", "unassigned:AMBIGUOUS", null, lookup.lookupCallOrder(), meeting);
        assertThat(result.success()).isTrue();
        var task = saved();
        assertThat(task.getUnresolvedAssignee().requestedAssigneeName()).isEqualTo("지영");
        assertThat(task.getUnresolvedAssignee().candidates()).hasSize(2);
        assertThat(task.getUnresolvedAssignee().warning()).isEqualTo("동명이인 확인 필요: \"지영\" (후보: 백엔드팀, 디자인팀)");
    }

    // 검증: 미등록 조회는 요청 이름을 후보 없는 스냅샷으로 저장한다.
    @Test
    void storesNotFoundLookupSnapshot() {
        var lookup = find.findMember("준호", meeting);
        assertThat(create.createTask("확인", "unassigned:NOT_FOUND", null, lookup.lookupCallOrder(), meeting).success()).isTrue();
        assertThat(saved().getUnresolvedAssignee().warning()).isEqualTo("등록되지 않은 담당자: \"준호\"");
    }

    // 검증: 팀 불일치는 회의록의 요청 팀과 실제 등록 팀을 함께 보존한다.
    @Test
    void storesTeamMismatchLookupSnapshot() {
        when(members.findAllByGivenNameOrderByIdAsc("해찬")).thenReturn(List.of(member(2L, "해찬", "백엔드팀")));
        var lookup = find.findMember("해찬", "프론트엔드팀", meeting);
        assertThat(create.createTask("확인", "unassigned:TEAM_MISMATCH", null, lookup.lookupCallOrder(), meeting).success()).isTrue();
        assertThat(saved().getUnresolvedAssignee().warning()).isEqualTo("팀 불일치 확인 필요: \"프론트엔드팀 해찬\" (등록: 백엔드팀 전해찬)");
    }

    // 검증: 참조 없는 미정 사유는 생성하지 않고 복구할 인자를 안내한다.
    @Test
    void rejectsMissingLookupReference() {
        assertInvalid(create.createTask("확인", "unassigned:NOT_FOUND", null, meeting));
    }

    // 검증: 0 이하 참조는 유효한 조회가 아니다.
    @Test
    void rejectsNonpositiveLookupReference() {
        assertInvalid(create.createTask("확인", "unassigned:NOT_FOUND", null, 0, meeting));
    }

    // 검증: 현재 회의의 다른 도구 호출 순번으로 미정을 증명할 수 없다.
    @Test
    void rejectsNonLookupReference() {
        execution.execute(meeting, "findTasks", Map.of(), () -> ToolResult.success("ok", "[]"));
        assertInvalid(create.createTask("확인", "unassigned:NOT_FOUND", null, 1, meeting));
    }

    // 검증: 다른 회의의 조회는 현재 회의의 등록 근거로 사용할 수 없다.
    @Test
    void rejectsLookupFromAnotherMeeting() {
        var lookup = find.findMember("준호", meeting(2L, "다른 회의"));
        assertInvalid(create.createTask("확인", "unassigned:NOT_FOUND", null, lookup.lookupCallOrder(), meeting));
    }

    // 검증: 미등록 조회를 동명이인 미정의 근거로 바꿔 쓸 수 없다.
    @Test
    void rejectsLookupWithWrongReason() {
        var lookup = find.findMember("준호", meeting);
        assertInvalid(create.createTask("확인", "unassigned:AMBIGUOUS", null, lookup.lookupCallOrder(), meeting));
    }

    // 검증: 조회가 여러 번 있어도 전달된 정확한 순번의 이름을 사용한다.
    @Test
    void resolvesSpecificLookupAmongMultipleCalls() {
        var first = find.findMember("준호", meeting);
        find.findMember("다른이름", meeting);
        assertThat(create.createTask("확인", "unassigned:NOT_FOUND", null, first.lookupCallOrder(), meeting).success()).isTrue();
        assertThat(saved().getUnresolvedAssignee().requestedAssigneeName()).isEqualTo("준호");
    }

    // 검증: 담당자 언급 없는 NONE은 조회 없이 저장하며 상세 사유가 없다.
    @Test
    void noneDoesNotRequireLookupOrSnapshot() {
        assertThat(create.createTask("확인", "unassigned:NONE", null, meeting).success()).isTrue();
        assertThat(saved().getUnresolvedAssignee()).isNull();
    }

    // 검증: 모델 인자에 없는 안전한 서버 경계에서 비밀·멘션·개행을 제거하고 이름 길이를 제한한다.
    @Test
    void sanitizesSecretsMentionsAndLongNames() {
        var lookup = find.findMember(String.join("", "@everyone\n s", "k-test-abcdefghijklmnopqrstuvwxyz ") + "가".repeat(150), meeting);
        var result = create.createTask("확인", "unassigned:NOT_FOUND", null, lookup.lookupCallOrder(), meeting);
        assertThat(result.success()).isTrue();
        String name = saved().getUnresolvedAssignee().requestedAssigneeName();
        assertThat(name).doesNotContain("\n", String.join("", "s", "k-test-")).contains("@everyone", "[REDACTED]");
        assertThat(name.codePointCount(0, name.length())).isLessThanOrEqualTo(100);
    }

    // 검증: 스냅샷은 최대 후보 20명과 생략 수를 보존하며 외부 목록 변경에 영향을 받지 않는다.
    @Test
    void boundsSnapshotCandidatesAndUnicodeLength() {
        var candidates = new ArrayList<UnresolvedAssignee.Candidate>();
        for (int i = 0; i < 25; i++) candidates.add(new UnresolvedAssignee.Candidate((long)i, "😀".repeat(110), "팀"));
        var snapshot = new UnresolvedAssignee("AMBIGUOUS", "이름", null, candidates, 2);
        candidates.clear();
        assertThat(snapshot.candidates()).hasSize(20);
        assertThat(snapshot.omittedCandidateCount()).isEqualTo(7);
        assertThat(snapshot.candidates().getFirst().name().codePointCount(0, snapshot.candidates().getFirst().name().length())).isEqualTo(100);
    }

    // 검증: JSON 변환기가 DB에 보존한 스냅샷을 손실 없이 되읽는다.
    @Test
    void snapshotJsonConverterRoundTrips() {
        var snapshot = new UnresolvedAssignee("NOT_FOUND", "준호", null, List.of(), 0);
        var converter = new UnresolvedAssignee.JsonConverter();
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(snapshot))).isEqualTo(snapshot);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // 검증: 기존 Task를 재사용할 때 원래 저장된 미정 사유를 덮어쓰지 않고 현재 조회는 결과에만 반환한다.
    @Test
    void existingTaskKeepsOriginalSnapshot() throws Exception {
        var original = new UnresolvedAssignee("NOT_FOUND", "원래이름", null, List.of(), 0);
        Task existing = Task.create("확인", null, null, meeting, original);
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(existing));
        var lookup = find.findMember("준호", meeting);
        var result = create.createTask("확인", "unassigned:NOT_FOUND", null, lookup.lookupCallOrder(), meeting);
        assertThat(result.success()).isTrue();
        assertThat(existing.getUnresolvedAssignee()).isEqualTo(original);
        assertThat(mapper.readTree(result.data()).path("unresolvedAssignee").path("requestedAssigneeName").asText()).isEqualTo("준호");
        verify(tasks, never()).save(any());
    }

    // 검증: 조회 도구가 아닌 일반 실행의 nullable 참조는 언박싱 오류 없이 유지한다.
    @Test
    void nonLookupToolKeepsNullLookupReference() {
        var result = execution.execute(meeting, "other", Map.of(), () -> ToolResult.success("ok", "[]"));
        assertThat(result.success()).isTrue();
        assertThat(result.lookupCallOrder()).isNull();
    }

    // 검증: 잘린 조회 로그는 추측하지 않고 재조회 참조를 안내한다.
    @Test
    void rejectsTruncatedLookupResult() {
        stored.add(ToolCallLog.success(meeting, "findMember", "{\"name\":\"지영\"}", "{\"data\":", 0, 1));
        assertInvalid(create.createTask("확인", "unassigned:AMBIGUOUS", null, 1, meeting));
    }

    // 검증: 결과가 없는 조회 로그도 일반화된 실행 오류 대신 참조 오류로 안내한다.
    @Test
    void rejectsLookupWithoutResult() {
        stored.add(ToolCallLog.failure(meeting, "findMember", "{\"name\":\"준호\"}", "MEMBER_NOT_FOUND", "없음", 0, 1));
        assertInvalid(create.createTask("확인", "unassigned:NOT_FOUND", null, 1, meeting));
    }

    // 검증: 실제 조회의 후보가 20명을 넘으면 Task에는 20명과 생략 수를 저장한다.
    @Test
    void boundsCandidatesFromRealLookup() {
        var candidates = new ArrayList<Member>();
        for (int i = 1; i <= 21; i++) candidates.add(member((long)i, "지영", "팀" + i));
        when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(candidates);
        var lookup = find.findMember("지영", meeting);
        assertThat(create.createTask("확인", "unassigned:AMBIGUOUS", null, lookup.lookupCallOrder(), meeting).success()).isTrue();
        var snapshot = saved().getUnresolvedAssignee();
        assertThat(snapshot.candidates()).hasSize(20);
        assertThat(snapshot.omittedCandidateCount()).isEqualTo(1);
    }

    // 검증: 실패 결과에 섞인 비밀과 원문은 모델과 로그 양쪽에서 같은 값으로 마스킹한다.
    @Test
    void sanitizesFailedResultForModelAndLog() throws Exception {
        String key = String.join("", "s", "k-test-abcdefghijklmnopqrstuvwxyz");
        var result = execution.execute(meeting, "findMember", Map.of("name", "준호"),
                () -> new ToolResult(false, "MEMBER_NOT_FOUND", key + meeting.getRawText(), key));
        assertThat(result.message()).doesNotContain(key, meeting.getRawText()).contains("[REDACTED]");
        assertThat(result.data()).isEqualTo("[REDACTED]");
        assertThat(mapper.readTree(stored.getFirst().getResult())).isEqualTo(mapper.valueToTree(result));
    }

    private void ambiguous() { when(members.findAllByGivenNameOrderByIdAsc("지영")).thenReturn(List.of(member(2L,"지영","백엔드팀"), member(3L,"지영","디자인팀"))); }
    private Task saved() { var captor = org.mockito.ArgumentCaptor.forClass(Task.class); verify(tasks).save(captor.capture()); return captor.getValue(); }
    private void assertInvalid(ToolResult result) { assertThat(result.errorCode()).isEqualTo("INVALID_LOOKUP_REFERENCE"); assertThat(result.message()).isEqualTo("findMember 결과에 있는 lookupCallOrder 값을 그대로 넣으세요"); verify(tasks, never()).save(any()); }
    private static Meeting meeting(Long id, String text) { var result = Meeting.create(text, MeetingSource.API_TEXT); ReflectionTestUtils.setField(result,"id",id); return result; }
    private static Member member(Long id, String name, String team) { var result = new Member(com.meetodo.MemberFixtures.familyNameForSampleGivenName(name), name, team, "U-"+id); ReflectionTestUtils.setField(result,"id",id); return result; }
}
