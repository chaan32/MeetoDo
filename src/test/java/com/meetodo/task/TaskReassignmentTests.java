package com.meetodo.task;

import com.meetodo.member.*;
import com.meetodo.task.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskReassignmentTests {
    static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    final TaskRepository tasks = mock(TaskRepository.class);
    final MemberRepository members = mock(MemberRepository.class);
    final TaskEventRepository history = mock(TaskEventRepository.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final TaskService service = new TaskService(tasks, members, mock(TaskMentionService.class),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    Member previous;
    Member next;
    Task task;

    @BeforeEach void setup() {
        service.setAdmins(new AdminAuthorization("UADMIN1"));
        service.setHistory(history);
        service.setEventPublisher(events);
        previous = member(2L, "UOWNER1");
        next = member(3L, "UNEXT1");
        task = Task.create("API 검토", previous, null).withId(1L, Instant.EPOCH);
        lenient().when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        lenient().when(members.findById(2L)).thenReturn(Optional.of(previous));
        lenient().when(members.findById(3L)).thenReturn(Optional.of(next));
    }

    Member member(long id, String slackId) {
        var member = new Member("김", "지영", "백엔드", slackId);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    // 관리자의 재지정은 기존 담당자 정보를 이벤트로 보존하고 감사 이력을 기록한다.
    @Test void adminReassignsAndRecordsPreviousAssignee() {
        var response = service.assignOrReassign(1L, 3L, "UADMIN1");
        assertThat(response.assignee().id()).isEqualTo(3L);
        assertThat(response.assignedBySlackUserId()).isEqualTo("UADMIN1");
        assertThat(response.assignedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new TaskReassignedEvent(1L, 2L, "UOWNER1", 3L, "UADMIN1"));
        var audit = ArgumentCaptor.forClass(TaskEvent.class);
        verify(history).save(audit.capture());
        assertThat(audit.getValue().getType()).isEqualTo("REASSIGNED");
        assertThat(audit.getValue().getBeforeValue()).isEqualTo("2");
        assertThat(audit.getValue().getAfterValue()).isEqualTo("3");
        assertThat(audit.getValue().getBySlackUserId()).isEqualTo("UADMIN1");
        assertThat(audit.getValue().getAt()).isEqualTo(NOW);
    }

    // 현재 담당자라도 관리자 권한 없이는 다른 사람에게 재지정하지 않는다.
    @Test void ownerCannotReassign() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UOWNER1"))
                .isInstanceOf(TaskMutationException.class).extracting("code").isEqualTo("TASK_REASSIGNMENT_FORBIDDEN");
        assertUnchanged();
    }

    // 일반 사용자는 담당자가 있는 업무를 재지정하지 않는다.
    @Test void otherUserCannotReassign() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UOTHER1"))
                .isInstanceOf(TaskMutationException.class);
        assertUnchanged();
    }

    // 같은 대상이어도 권한 확인을 먼저 한다.
    @Test void unauthorizedSameTargetIsRejected() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, 2L, "UOWNER1"))
                .isInstanceOf(TaskMutationException.class);
        assertUnchanged();
    }

    // 관리자가 같은 담당자를 고르면 메타데이터·감사·알림이 바뀌지 않는다.
    @Test void adminSameTargetIsNoop() {
        task.assign(previous, "UFIRST1", Instant.EPOCH);
        var response = service.assignOrReassign(1L, 2L, "UADMIN1");
        assertThat(response.assignedBySlackUserId()).isEqualTo("UFIRST1");
        assertThat(response.assignedAt()).isEqualTo(Instant.EPOCH);
        assertUnchanged();
    }

    // 최초 지정 버튼은 관리자에게도 재지정 수단이 되지 않는다.
    @Test void staleInitialAssignmentNeverReassigns() {
        assertThat(service.assign(1L, 3L, "UADMIN1").assignee().id()).isEqualTo(2L);
        assertUnchanged();
        verifyNoInteractions(members);
    }

    // 완료된 업무는 관리자 재지정도 충돌 오류로 거절한다.
    @Test void rejectsDoneAssignment() {
        task.changeStatus(TaskStatus.DONE, "UOWNER1", Instant.EPOCH);
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UADMIN1"))
                .isInstanceOf(TaskMutationException.class).extracting("code").isEqualTo("TASK_NOT_ASSIGNABLE");
        assertUnchanged();
    }

    // 삭제된 업무는 재지정 경로에서도 없는 업무로 취급한다.
    @Test void rejectsDeletedAssignment() {
        task.softDelete();
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UADMIN1"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertUnchanged();
    }

    // 비활성 팀원을 대상으로 한 재지정은 원본 담당자와 이력을 보존한다.
    @Test void rejectsInactiveTarget() {
        next.deactivate();
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UADMIN1"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertUnchanged();
    }

    // 없는 팀원 ID는 새 담당자로 저장하지 않는다.
    @Test void rejectsMissingTarget() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, 99L, "UADMIN1"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertUnchanged();
    }

    // 직접 호출에서 누락된 ID도 검증 오류로 거절한다.
    @Test void rejectsNullTarget() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, null, "UADMIN1"))
                .isInstanceOf(TaskAssignmentException.class);
        assertUnchanged();
    }

    // 새 담당자에게 동일 TODO가 있으면 중복 ID와 함께 거절하고 변경·이벤트를 남기지 않는다.
    @Test void rejectsDuplicateWithoutMutationOrEvents() {
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(next, TaskStatus.TODO))
                .thenReturn(List.of(Task.create(" api   검토 ", next, null).withId(9L, Instant.EPOCH)));
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UADMIN1"))
                .isInstanceOf(DuplicateTaskException.class).extracting("existingTaskId").isEqualTo(9L);
        assertUnchanged();
    }

    // 최초 지정 당시 후보 이력은 관리자 재지정 후에도 보존하며 재지정 대상을 제한하지 않는다.
    @Test void reassignmentPreservesOriginalUnresolvedSnapshot() {
        var snapshot = new UnresolvedAssignee("AMBIGUOUS", "지영", null,
                List.of(new UnresolvedAssignee.Candidate(2L, "지영", "백엔드")), 0);
        task = Task.create("API 검토", null, null, null, snapshot).withId(1L, Instant.EPOCH);
        task.assign(previous, "UFIRST1", Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        service.assignOrReassign(1L, 3L, "UADMIN1");
        assertThat(task.getUnresolvedAssignee()).isEqualTo(snapshot);
        assertThat(task.getAssignee()).isSameAs(next);
    }

    // 미정 업무 최초 지정은 관리자 여부와 무관하게 동일 후보 검증을 통과한다.
    @Test void unassignedTodoAllowsInitialAssignmentByAnyone() {
        task = Task.create("API 검토", null, null).withId(1L, Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        assertThat(service.assignOrReassign(1L, 3L, "UOTHER1").assignee().id()).isEqualTo(3L);
        verify(events).publishEvent(new TaskAssignedEvent(1L, "UOTHER1"));
    }

    // 미정 업무의 최초 지정에서는 기존 동명이인 후보 제한을 우회하지 않는다.
    @Test void initialAssignmentStillRejectsOutsideCandidate() {
        var snapshot = new UnresolvedAssignee("AMBIGUOUS", "지영", null,
                List.of(new UnresolvedAssignee.Candidate(2L, "지영", "백엔드")), 0);
        task = Task.create("API 검토", null, null, null, snapshot).withId(1L, Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "UADMIN1"))
                .isInstanceOf(TaskAssignmentException.class);
        assertThat(task.getAssignee()).isNull();
        verifyNoInteractions(history, events);
    }

    // 중복 후보 조회에 자기 행이 포함되어도 자신을 중복으로 판단하지 않는다.
    @Test void duplicateLookupExcludesCurrentTask() {
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(next, TaskStatus.TODO)).thenReturn(List.of(task));
        assertThat(service.assignOrReassign(1L, 3L, "UADMIN1").assignee().id()).isEqualTo(3L);
    }

    // 잘못된 변경자 형식은 잠금을 얻기 전에 거절한다.
    @Test void rejectsMalformedActorBeforeLock() {
        assertThatThrownBy(() -> service.assignOrReassign(1L, 3L, "U-DEV"))
                .isInstanceOf(TaskMutationException.class);
        verifyNoInteractions(tasks, events, history);
    }

    void assertUnchanged() {
        assertThat(task.getAssignee()).isSameAs(previous);
        verify(tasks, never()).save(any());
        verifyNoInteractions(history, events);
    }
}
