package com.meetodo.task;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.task.exception.DuplicateTaskException;
import com.meetodo.task.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskAssignmentTests {
    static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    final TaskRepository tasks = mock(TaskRepository.class);
    final MemberRepository members = mock(MemberRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final TaskService service = new TaskService(tasks, members, mock(TaskMentionService.class),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    Member member;

    @BeforeEach void setup() {
        service.setEventPublisher(events);
        member = new Member("전", "해찬", "백엔드", "UMEMBER1");
        ReflectionTestUtils.setField(member, "id", 2L);
        lenient().when(members.findById(2L)).thenReturn(Optional.of(member));
    }

    Task task(UnresolvedAssignee snapshot) {
        var task = Task.create("API 명세 검토", null, null, null, snapshot).withId(1L, Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        return task;
    }
    UnresolvedAssignee snapshot(String reason, Long candidateId) {
        return new UnresolvedAssignee(reason, "해찬", "백엔드",
                candidateId == null ? List.of() : List.of(new UnresolvedAssignee.Candidate(candidateId, "해찬", "백엔드")), 0);
    }

    // 미정 TODO는 다른 사용자가 지정해도 담당자·지정자·고정 시각을 기록하고 한 번 알린다.
    @Test void anyoneCanAssignAndRecordsMetadataAndEvent() {
        var task = task(null);
        var response = service.assign(1L, 2L, "WOTHER1");
        assertThat(task.getAssignee()).isSameAs(member);
        assertThat(response.assignedBySlackUserId()).isEqualTo("WOTHER1");
        assertThat(response.assignedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new TaskAssignedEvent(1L, "WOTHER1"));
        verify(tasks).findByIdForUpdate(1L);
    }

    // 모호한 이름에서 저장한 후보는 지정할 수 있고 미정 이력은 남기되 경고는 숨긴다.
    @Test void ambiguousCandidatePreservesHistoryAndHidesWarning() {
        var snapshot = snapshot("AMBIGUOUS", 2L);
        var task = task(snapshot);
        var response = service.assign(1L, 2L, "UACTOR1");
        assertThat(task.getUnresolvedAssignee()).isEqualTo(snapshot);
        assertThat(response.unresolvedAssignee()).isNull();
        assertThat(response.warning()).isNull();
        assertThat(response.unassignedReason()).isNull();
    }

    // 동명이인 후보 밖 ID는 active여도 지정하지 않는다.
    @Test void rejectsMemberOutsideAmbiguousSnapshot() {
        var task = task(snapshot("AMBIGUOUS", 3L));
        assertThatThrownBy(() -> service.assign(1L, 2L, "UACTOR1"))
                .isInstanceOf(TaskAssignmentException.class).extracting("code").isEqualTo("ASSIGNEE_NOT_CANDIDATE");
        assertThat(task.getAssignee()).isNull();
        verifyNoInteractions(events);
    }

    // 팀 불일치도 저장한 후보만 지정한다.
    @Test void rejectsMemberOutsideTeamMismatchSnapshot() {
        task(snapshot("TEAM_MISMATCH", 3L));
        assertThatThrownBy(() -> service.assign(1L, 2L, "UACTOR1")).isInstanceOf(TaskAssignmentException.class);
    }

    // 팀 불일치 후보라도 사용자가 명시적으로 고르면 지정한다.
    @Test void acceptsStoredTeamMismatchCandidate() {
        task(snapshot("TEAM_MISMATCH", 2L));
        assertThat(service.assign(1L, 2L, "UACTOR1").assignee().id()).isEqualTo(2L);
    }

    // 미등록 이름은 이력에 후보가 없어도 active 팀원을 지정한다.
    @Test void notFoundAllowsAnyActiveMember() {
        task(snapshot("NOT_FOUND", null));
        assertThat(service.assign(1L, 2L, "UACTOR1").assignee().id()).isEqualTo(2L);
    }

    // 담당자 언급이 없던 업무도 active 팀원을 지정한다.
    @Test void noneAllowsAnyActiveMember() {
        task(snapshot("NONE", null));
        assertThat(service.assign(1L, 2L, "UACTOR1").assignee().id()).isEqualTo(2L);
    }

    // 비활성화된 과거 후보는 지정하지 않는다.
    @Test void rejectsInactiveCandidate() {
        task(snapshot("AMBIGUOUS", 2L));
        member.deactivate();
        assertThatThrownBy(() -> service.assign(1L, 2L, "UACTOR1")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(events);
    }

    // 삭제되었거나 없는 팀원 ID는 지정하지 않는다.
    @Test void rejectsMissingMember() {
        task(null);
        assertThatThrownBy(() -> service.assign(1L, 99L, "UACTOR1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // 지정 후 동일 담당자의 정규화 제목 TODO와 충돌하면 원래 미정 상태를 보존한다.
    @Test void rejectsDuplicateAssignedTodo() {
        var task = task(null);
        var duplicate = Task.create(" api   명세 검토 ", member, null).withId(3L, Instant.EPOCH);
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO)).thenReturn(List.of(duplicate));
        assertThatThrownBy(() -> service.assign(1L, 2L, "UACTOR1"))
                .isInstanceOf(DuplicateTaskException.class).extracting("existingTaskId").isEqualTo(3L);
        assertThat(task.getAssignee()).isNull();
        assertThat(task.getAssignedAt()).isNull();
        verifyNoInteractions(events);
    }

    // 다른 제목의 TODO는 지정 충돌이 아니다.
    @Test void allowsDifferentTitleForSameMember() {
        task(null);
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(member, TaskStatus.TODO))
                .thenReturn(List.of(Task.create("다른 업무", member, null).withId(3L, Instant.EPOCH)));
        assertThat(service.assign(1L, 2L, "UACTOR1").assignee().id()).isEqualTo(2L);
    }

    // 이미 지정한 업무를 다시 지정해도 첫 지정자와 시각, 담당자를 바꾸지 않는다.
    @Test void repeatAssignmentPreservesFirstMutation() {
        task(null);
        service.assign(1L, 2L, "UACTOR1");
        var response = service.assign(1L, 99L, "UACTOR2");
        assertThat(response.assignee().id()).isEqualTo(2L);
        assertThat(response.assignedBySlackUserId()).isEqualTo("UACTOR1");
        assertThat(response.assignedAt()).isEqualTo(NOW);
        verify(events, times(1)).publishEvent(any(TaskAssignedEvent.class));
        verify(members, never()).findById(99L);
    }

    // 완료된 미정 업무는 지정하지 않고 이벤트도 내보내지 않는다.
    @Test void doneTaskRemainsUnassigned() {
        var task = task(null);
        task.changeStatus(TaskStatus.DONE);
        assertThat(service.assign(1L, 2L, "UACTOR1").assignee()).isNull();
        verifyNoInteractions(members, events);
    }

    // 없는 업무 ID에는 공통 404 예외를 사용한다.
    @Test void rejectsMissingTask() {
        assertThatThrownBy(() -> service.assign(99L, 2L, "UACTOR1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // 개발용 가짜 Slack ID는 지정 이력에 남기지 않는다.
    @Test void rejectsFakeActorBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.assign(1L, 2L, "U-DEV-HAECHAN")).isInstanceOf(TaskAssignmentException.class);
        verifyNoInteractions(tasks, events);
    }

    // 누락된 지정자 ID는 저장소 조회 전에 거절한다.
    @Test void rejectsNullActor() {
        assertThatThrownBy(() -> service.assign(1L, 2L, null)).isInstanceOf(TaskAssignmentException.class);
        verifyNoInteractions(tasks);
    }

    // DB 길이 제한을 넘는 ID는 거절한다.
    @Test void rejectsOversizedActor() {
        assertThatThrownBy(() -> service.assign(1L, 2L, "U" + "A".repeat(80))).isInstanceOf(TaskAssignmentException.class);
    }

    // 저장 가능한 최대 길이 Slack ID는 허용한다.
    @Test void acceptsMaximumActorLength() {
        task(null);
        assertThat(service.assign(1L, 2L, "U" + "A".repeat(79)).assignedAt()).isEqualTo(NOW);
    }

    // 서비스 경로도 잘못된 담당자 ID를 거절한다.
    @Test void rejectsNonPositiveMemberId() {
        task(null);
        assertThatThrownBy(() -> service.assign(1L, 0L, "UACTOR1")).isInstanceOf(TaskAssignmentException.class);
    }
}
