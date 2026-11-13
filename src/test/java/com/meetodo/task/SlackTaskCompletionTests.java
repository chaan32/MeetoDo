package com.meetodo.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import com.meetodo.task.dto.UpdateTaskRequest;
import com.meetodo.task.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackTaskCompletionTests {
    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final TaskService service = new TaskService(tasks, mock(MemberRepository.class), mock(TaskMentionService.class), clock);

    private Task task(Member assignee) {
        Task task = Task.create("완료 대상", assignee, null).withId(1L, Instant.EPOCH);
        when(tasks.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        lenient().when(tasks.save(task)).thenReturn(task);
        return task;
    }

    // PATCH 완료 시각이 응답에 포함되고 주입한 Clock을 사용한다.
    @Test void patchRecordsCompletionTime() {
        task(null);
        var request = new UpdateTaskRequest();
        request.setStatus(TaskStatus.DONE);
        var response = service.update(1L, request, "U123ABC");
        var json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(response);
        assertThat(json.hasNonNull("completedAt")).isTrue();
        assertThat(response.completedAt()).isEqualTo(NOW);
        assertThat(response.completedBySlackUserId()).isEqualTo("U123ABC");
    }

    // 담당자는 자신의 할 일을 완료할 수 있다.
    @Test void assignedOwnerCanComplete() {
        task(new Member("전", "해찬", "백엔드팀", "U123ABC"));
        var response = service.completeFromSlack(1L, "U123ABC");
        assertThat(response.status()).isEqualTo(TaskStatus.DONE);
        assertThat(response.completedBySlackUserId()).isEqualTo("U123ABC");
        assertThat(response.completedAt()).isEqualTo(NOW);
    }

    // 다른 사용자는 TODO를 변경할 수 없다.
    @Test void rejectsOtherUserForAssignedTask() {
        var task = task(new Member("전", "해찬", "백엔드팀", "U123ABC"));
        assertThatThrownBy(() -> service.completeFromSlack(1L, "U456DEF"))
                .isInstanceOf(TaskCompletionForbiddenException.class).hasMessage("담당자만 완료할 수 있어요");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        verify(tasks, never()).save(any());
    }

    // 담당자 미정은 유효한 Slack 사용자 누구나 완료한다.
    @Test void anyoneCanCompleteUnassignedTask() {
        task(null);
        assertThat(service.completeFromSlack(1L, "W123ABC").completedBySlackUserId()).isEqualTo("W123ABC");
    }

    // 이미 완료된 버튼을 다른 사용자가 눌러도 첫 완료자와 시각이 유지된다.
    @Test void repeatedCompletionPreservesFirstActorAndTime() {
        var task = task(new Member("전", "해찬", "백엔드팀", "U123ABC"));
        task.changeStatus(TaskStatus.DONE, "U123ABC", Instant.EPOCH);
        var response = service.completeFromSlack(1L, "U456DEF");
        assertThat(response.completedBySlackUserId()).isEqualTo("U123ABC");
        assertThat(response.completedAt()).isEqualTo(Instant.EPOCH);
        verify(tasks, never()).save(any());
    }

    // 존재하지 않는 ID는 공통 TASK_NOT_FOUND 예외를 사용한다.
    @Test void rejectsMissingTask() {
        assertThatThrownBy(() -> service.completeFromSlack(99L, "U123ABC"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // 가짜 개발 ID로 완료자를 기록하지 않는다.
    @Test void rejectsFakeActor() {
        assertThatThrownBy(() -> service.completeFromSlack(1L, "U-DEV-HAECHAN"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks);
    }

    // 빈 사용자 ID는 저장소 조회 전에 거절한다.
    @Test void rejectsEmptyActor() {
        assertThatThrownBy(() -> service.completeFromSlack(1L, ""))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks);
    }

    // 누락된 사용자 ID도 저장소 조회 전에 거절한다.
    @Test void rejectsNullActor() {
        assertThatThrownBy(() -> service.completeFromSlack(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks);
    }

    // DB 컬럼 한도를 초과하는 사용자 ID는 거절한다.
    @Test void rejectsOversizedActor() {
        assertThatThrownBy(() -> service.completeFromSlack(1L, "U" + "A".repeat(80)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tasks);
    }

    // 허용된 최대 길이 사용자 ID는 그대로 기록한다.
    @Test void acceptsMaximumLengthActor() {
        task(null);
        String actor = "U" + "A".repeat(79);
        assertThat(service.completeFromSlack(1L, actor).completedBySlackUserId()).isEqualTo(actor);
    }

    // Slack ID가 없는 담당자는 타인의 완료를 허용하지 않는다.
    @Test void rejectsActorWhenAssigneeHasNoSlackId() {
        task(new Member("전", "해찬", "백엔드팀", null));
        assertThatThrownBy(() -> service.completeFromSlack(1L, "U123ABC"))
                .isInstanceOf(TaskCompletionForbiddenException.class);
    }

    // 재개하면 완료 메타데이터를 함께 지운다.
    @Test void reopeningClearsCompletionMetadata() {
        var task = task(null);
        task.changeStatus(TaskStatus.DONE, "U123ABC", Instant.EPOCH);
        var request = new UpdateTaskRequest();
        request.setStatus(TaskStatus.TODO);
        var response = service.update(1L, request, "U123ABC");
        assertThat(response.status()).isEqualTo(TaskStatus.TODO);
        assertThat(response.completedAt()).isNull();
        assertThat(response.completedBySlackUserId()).isNull();
    }

    // PATCH 중복 완료도 기존 완료 정보를 덮어쓰지 않는다.
    @Test void repeatedPatchPreservesCompletionMetadata() {
        var task = task(null);
        task.changeStatus(TaskStatus.DONE, "U123ABC", Instant.EPOCH);
        var request = new UpdateTaskRequest();
        request.setStatus(TaskStatus.DONE);
        var response = service.update(1L, request, "U123ABC");
        assertThat(response.completedAt()).isEqualTo(Instant.EPOCH);
        assertThat(response.completedBySlackUserId()).isEqualTo("U123ABC");
    }

    // 회의 결과도 최신 Task의 상태·Slack ID·완료 정보를 전달한다.
    @Test void meetingTaskCarriesCurrentCompletionState() {
        var task = task(new Member("전", "해찬", "백엔드팀", "U123ABC"));
        task.changeStatus(TaskStatus.DONE, "U123ABC", NOW);
        var response = MeetingTaskResponse.from(task, "CREATED", 1, null, null);
        assertThat(response.status()).isEqualTo(TaskStatus.DONE);
        assertThat(response.assigneeSlackUserId()).isEqualTo("U123ABC");
        assertThat(response.completedBySlackUserId()).isEqualTo("U123ABC");
        assertThat(response.completedAt()).isEqualTo(NOW);
    }
}
