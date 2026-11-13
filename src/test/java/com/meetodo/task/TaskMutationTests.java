package com.meetodo.task;

import com.meetodo.member.*;
import com.meetodo.task.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskMutationTests {
    static final Instant NOW = Instant.parse("2026-11-13T15:00:00Z"); // 서울은 14일이다.
    final TaskRepository tasks = mock(TaskRepository.class);
    final MemberRepository members = mock(MemberRepository.class);
    final TaskEventRepository history = mock(TaskEventRepository.class);
    final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    final TaskService service = new TaskService(tasks, members, mock(TaskMentionService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    final Member owner = new Member("전", "해찬", "백엔드", "UOWNER1");
    @BeforeEach void setup() {
        service.setHistory(history); service.setEventPublisher(publisher);
        service.setAdmins(new AdminAuthorization("UADMIN1"));
    }
    Task task(Member member) {
        Task task = Task.create("API 검토", member, LocalDate.of(2026, 11, 20)).withId(1L, Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        return task;
    }
    TaskEvent audit() {
        var captured = ArgumentCaptor.forClass(TaskEvent.class); verify(history).save(captured.capture()); return captured.getValue();
    }

    // 미정 업무의 제목 변경은 누구나 가능하며 변경 전후·지정자·서울 기준 Clock 시각을 남긴다.
    @Test void editsUnassignedTitleWithAudit() {
        task(null);
        var response = service.edit(1L, "  새 API 검토. ", false, null, "UOTHER1");
        assertThat(response.title()).isEqualTo("새 API 검토");
        assertThat(response.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(audit().getType()).isEqualTo("TITLE_CHANGED");
        assertThat(audit().getBeforeValue()).isEqualTo("API 검토");
        assertThat(audit().getAfterValue()).isEqualTo("새 API 검토");
        assertThat(audit().getBySlackUserId()).isEqualTo("UOTHER1");
        assertThat(audit().getAt()).isEqualTo(NOW);
        verify(publisher).publishEvent(new TaskChangedEvent(1L));
    }

    // 담당자는 자신에게 지정된 업무를 수정한다.
    @Test void ownerCanEdit() {
        task(owner);
        assertThat(service.edit(1L, "명세 검토", false, null, "UOWNER1").title()).isEqualTo("명세 검토");
    }

    // 관리자는 다른 담당자의 업무를 수정한다.
    @Test void adminCanEditAssignedTask() {
        task(owner);
        assertThat(service.edit(1L, "명세 검토", false, null, "UADMIN1").title()).isEqualTo("명세 검토");
    }

    // 다른 사용자는 담당자가 지정된 업무를 변경하거나 이력을 남길 수 없다.
    @Test void otherUserCannotEditAssignedTask() {
        var task = task(owner);
        assertThatThrownBy(() -> service.edit(1L, "명세 검토", false, null, "UOTHER1"))
                .isInstanceOf(TaskMutationException.class).extracting("code").isEqualTo("TASK_MUTATION_FORBIDDEN");
        assertThat(task.getTitle()).isEqualTo("API 검토"); verifyNoInteractions(history, publisher);
    }

    // Slack 연결이 없는 담당자는 관리자만 변경한다.
    @Test void missingOwnerSlackIdDoesNotGrantOtherUsersAccess() {
        task(new Member("전", "해찬", "백엔드", null));
        assertThatThrownBy(() -> service.delete(1L, "UOTHER1")).isInstanceOf(TaskMutationException.class);
    }

    // 명시적인 null 날짜는 마감일을 비우고 날짜만의 이력을 남긴다.
    @Test void clearsDateOnlyWhenProvided() {
        task(null);
        assertThat(service.edit(1L, null, true, null, "UOTHER1").dueDate()).isNull();
        assertThat(audit().getType()).isEqualTo("DUE_DATE_CHANGED");
        assertThat(audit().getBeforeValue()).isEqualTo("2026-11-20");
        assertThat(audit().getAfterValue()).isNull();
    }

    // UTC로 아직 오늘이어도 서울 날짜상 과거인 마감일은 거절하고 제목도 그대로 보존한다.
    @Test void rejectsPastDateBeforeAnyMutationUsingSeoulToday() {
        var task = task(null);
        assertThatThrownBy(() -> service.edit(1L, "새 API 검토", true, LocalDate.of(2026, 11, 13), "UOTHER1"))
                .isInstanceOf(TaskMutationException.class).extracting("code").isEqualTo("INVALID_DUE_DATE");
        assertThat(task.getTitle()).isEqualTo("API 검토"); verifyNoInteractions(history, publisher);
    }

    // 서울 기준 오늘은 과거가 아니므로 허용한다.
    @Test void acceptsSeoulToday() {
        task(null);
        assertThat(service.edit(1L, null, true, LocalDate.of(2026, 11, 14), "UOTHER1").dueDate())
                .isEqualTo(LocalDate.of(2026, 11, 14));
    }

    // 기존 과거 날짜를 수정하지 않으면 자동으로 지우거나 거절하지 않는다.
    @Test void preservesExistingPastDateWhenOmitted() {
        var task = task(null); task.edit(task.getTitle(), LocalDate.of(2020, 1, 1));
        assertThat(service.edit(1L, "새 API 검토", false, null, "UOTHER1").dueDate()).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    // 문장형 제목은 기존 명사형 검증으로 거절한다.
    @Test void rejectsSentenceTitle() {
        task(null);
        assertThatThrownBy(() -> service.edit(1L, "API를 검토한다.", false, null, "UOTHER1"))
                .isInstanceOf(InvalidTaskTitleException.class); verifyNoInteractions(history);
    }

    // 값이 달라지지 않은 수정은 감사 이력과 갱신 이벤트를 만들지 않는다.
    @Test void noOpEditHasNoHistoryOrEvent() {
        task(null); service.edit(1L, " API 검토. ", false, null, "UOTHER1");
        verifyNoInteractions(history, publisher); verify(tasks, never()).save(any());
    }

    // 자기 자신이 후보 첫 번째여도 다른 중복 TODO를 반드시 찾아 거절한다.
    @Test void excludesSelfBeforeFindingAnotherDuplicate() {
        var task = task(null);
        var other = Task.create("새 API 검토", null, null).withId(2L, Instant.EPOCH);
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(task, other));
        assertThatThrownBy(() -> service.edit(1L, "새 API 검토", false, null, "UOTHER1"))
                .isInstanceOf(DuplicateTaskException.class).extracting("existingTaskId").isEqualTo(2L);
        assertThat(task.getTitle()).isEqualTo("API 검토"); verifyNoInteractions(history);
    }

    // 삭제된 업무는 중복 검사의 대상이 아니다.
    @Test void deletedTaskDoesNotBlockEdit() {
        task(null); var deleted = Task.create("새 API 검토", null, null).withId(2L, Instant.EPOCH); deleted.softDelete();
        when(tasks.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(deleted));
        assertThat(service.edit(1L, "새 API 검토", false, null, "UOTHER1").title()).isEqualTo("새 API 검토");
    }

    // 재개는 완료 메타데이터를 제거하고 상태 변경 이력을 남긴다.
    @Test void reopensDoneTaskAndClearsCompletion() {
        var task = task(owner); task.changeStatus(TaskStatus.DONE, "UOWNER1", Instant.EPOCH);
        var response = service.reopen(1L, "UOWNER1");
        assertThat(response.status()).isEqualTo(TaskStatus.TODO); assertThat(response.completedAt()).isNull();
        assertThat(response.completedBySlackUserId()).isNull(); assertThat(audit().getBeforeValue()).isEqualTo("DONE");
        assertThat(audit().getAfterValue()).isEqualTo("TODO");
    }

    // 같은 담당자의 기존 TODO가 있으면 완료 상태를 유지한다.
    @Test void rejectsReopenDuplicateBeforeChangingDone() {
        var task = task(owner); task.changeStatus(TaskStatus.DONE, "UOWNER1", Instant.EPOCH);
        var other = Task.create("api 검토", owner, null).withId(2L, Instant.EPOCH);
        when(tasks.findAllByAssigneeAndStatusOrderByCreatedAtDesc(owner, TaskStatus.TODO)).thenReturn(List.of(other));
        assertThatThrownBy(() -> service.reopen(1L, "UOWNER1")).isInstanceOf(DuplicateTaskException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.DONE); assertThat(task.getCompletedAt()).isEqualTo(Instant.EPOCH);
        verifyNoInteractions(history, publisher);
    }

    // 이미 미완료인 업무 재개는 추가 이력이 없다.
    @Test void noOpReopenHasNoEvent() {
        task(null); service.reopen(1L, "UOTHER1"); verifyNoInteractions(history, publisher);
    }

    // 삭제는 레코드를 남기고 표시만 바꾸며 감사 이력을 보존한다.
    @Test void softDeletesAndRecordsEvent() {
        var task = task(null); var response = service.delete(1L, "UOTHER1");
        assertThat(task.isDeleted()).isTrue(); assertThat(response.deleted()).isTrue();
        assertThat(audit().getType()).isEqualTo("DELETED"); verify(tasks, never()).delete(any());
        verify(publisher).publishEvent(new TaskChangedEvent(1L));
    }

    // 삭제된 업무는 수정 대상에서 404로 제외한다.
    @Test void rejectsEditingDeletedTask() {
        var task = task(null); task.softDelete();
        assertThatThrownBy(() -> service.edit(1L, "새 제목", false, null, "UOTHER1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // 삭제된 업무는 담당자 지정도 거절한다.
    @Test void rejectsAssigningDeletedTask() {
        var task = task(null); task.softDelete();
        assertThatThrownBy(() -> service.assign(1L, 2L, "UOTHER1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // 삭제된 업무는 완료도 거절한다.
    @Test void rejectsCompletingDeletedTask() {
        var task = task(null); task.softDelete();
        assertThatThrownBy(() -> service.completeFromSlack(1L, "UOTHER1")).isInstanceOf(ResourceNotFoundException.class);
    }

    // 관리자는 타인 업무를 완료할 수 있고 지정자·시각 이력이 남는다.
    @Test void adminCanCompleteAndRecordsAudit() {
        task(owner);
        assertThat(service.completeFromSlack(1L, "UADMIN1").completedBySlackUserId()).isEqualTo("UADMIN1");
        assertThat(audit().getType()).isEqualTo("STATUS_CHANGED"); verify(publisher).publishEvent(new TaskChangedEvent(1L));
    }

    // 가짜 Slack ID는 업무 조회 전에 거절한다.
    @Test void rejectsFakeActorBeforeLock() {
        assertThatThrownBy(() -> service.delete(1L, "U-DEV")).isInstanceOf(TaskMutationException.class); verifyNoInteractions(tasks);
    }

    // modal 열기 검사도 같은 권한 규칙을 적용한다.
    @Test void modalAuthorizationRejectsOtherOwner() {
        var task = Task.create("API 검토", owner, null).withId(1L, Instant.EPOCH);
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> service.authorizeMutation(1L, "UOTHER1")).isInstanceOf(TaskMutationException.class);
    }
}
