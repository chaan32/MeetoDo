package com.meetodo.task;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.task.dto.CreateTaskRequest;
import com.meetodo.task.dto.UpdateTaskStatusRequest;
import com.meetodo.task.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class TaskServiceTests {

    @Mock TaskRepository taskRepository;
    @Mock MemberRepository memberRepository;
    @Mock TaskMentionService taskMentionService;
    TaskService taskService;

    @org.junit.jupiter.api.BeforeEach void setUp() {
        taskService = new TaskService(taskRepository, memberRepository, taskMentionService,
                java.time.Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), java.time.ZoneId.of("Asia/Seoul")));
    }

    // 검증: createsTaskWithoutAssignee 시나리오의 기대 동작을 확인한다.
    @Test
    void createsTaskWithoutAssignee() {
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task task = invocation.getArgument(0);
            return task.withId(1L, Instant.parse("2026-11-10T01:00:00Z"));
        });

        var result = taskService.create(new CreateTaskRequest("회의록 정리", null, LocalDate.of(2026, 11, 20)));

        assertThat(result.assignee()).isNull();
        assertThat(result.status()).isEqualTo(TaskStatus.TODO);
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-11-10T01:00:00Z"));
    }

    // 검증: rejectsUnknownAssignee 시나리오의 기대 동작을 확인한다.
    @Test
    void rejectsUnknownAssignee() {
        when(memberRepository.findAllByGivenNameOrderByIdAsc("없는 사람")).thenReturn(List.of());

        assertThatThrownBy(() -> taskService.create(
                new CreateTaskRequest("작업", "없는 사람", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // 검증: filtersTasksByAssigneeName 시나리오의 기대 동작을 확인한다.
    @Test
    void filtersTasksByAssigneeName() {
        Member member = new Member("김", "민수", "미지정", "U-MINSU");
        Task task = Task.create("설계서 작성", member, null);
        when(memberRepository.findAllByGivenNameOrderByIdAsc("민수")).thenReturn(List.of(member));
        when(taskRepository.findAllByAssigneeOrderByCreatedAtDesc(member)).thenReturn(java.util.List.of(task));

        var result = taskService.findAll("민수");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().assignee().name()).isEqualTo("김민수");
    }

    // 검증: changesTaskStatus 시나리오의 기대 동작을 확인한다.
    @Test
    void changesTaskStatus() {
        Task task = Task.create("설계서 작성", null, null);
        when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        var result = taskService.updateStatus(1L, new UpdateTaskStatusRequest(TaskStatus.DONE));

        assertThat(result.status()).isEqualTo(TaskStatus.DONE);
        verify(taskRepository).save(task);
    }

    // 검증: reusesSameAssigneeAndTitleAcrossMeetings 시나리오의 기대 동작을 확인한다.
    @Test
    void reusesSameAssigneeAndTitleAcrossMeetings() {
        Member assignee = new Member("김", "민수", "미지정", "U-MINSU");
        Meeting firstMeeting = meetingWithId(10L);
        Meeting secondMeeting = meetingWithId(11L);
        Task existing = Task.create("설계서  작성", assignee, null, firstMeeting).withId(1L, Instant.EPOCH);
        when(taskRepository.findAllByAssigneeAndStatusOrderByCreatedAtDesc(assignee, TaskStatus.TODO))
                .thenReturn(java.util.List.of(existing));

        var repeated = taskService.createFromToolIdempotently(" 설계서 작성 ", assignee, null, firstMeeting);
        var otherMeeting = taskService.createFromToolIdempotently("설계서 작성", assignee, null, secondMeeting);

        assertThat(repeated.created()).isFalse();
        assertThat(repeated.task().id()).isEqualTo(1L);
        assertThat(otherMeeting.created()).isFalse();
        assertThat(otherMeeting.task().id()).isEqualTo(1L);
        assertThat(otherMeeting.task().meetingId()).isEqualTo(10L);
        verify(taskRepository, never()).save(any(Task.class));
    }

    // 검증: deduplicatesUnassignedTasksWithTheSameMeetingAndTitle 시나리오의 기대 동작을 확인한다.
    @Test
    void deduplicatesUnassignedTasksWithTheSameMeetingAndTitle() {
        Meeting meeting = meetingWithId(10L);
        Task existing = Task.create("자료 공유", null, null, meeting).withId(2L, Instant.EPOCH);
        when(taskRepository.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO))
                .thenReturn(java.util.List.of(existing));

        var result = taskService.createFromToolIdempotently("자료 공유", null, null, meeting);

        assertThat(result.created()).isFalse();
        assertThat(result.task().id()).isEqualTo(2L);
        verify(taskRepository, never()).save(any(Task.class));
    }

    // 검증: createsNewTaskWhenMatchingTaskIsDone 시나리오의 기대 동작을 확인한다.
    @Test
    void createsNewTaskWhenMatchingTaskIsDone() {
        Member assignee = new Member("김", "민수", "미지정", "U-MINSU");
        Meeting previousMeeting = meetingWithId(10L);
        Meeting meeting = meetingWithId(11L);
        Task done = Task.create("설계서 작성", assignee, null, previousMeeting).withId(3L, Instant.EPOCH);
        done.changeStatus(TaskStatus.DONE);
        when(taskRepository.findAllByAssigneeAndStatusOrderByCreatedAtDesc(assignee, TaskStatus.TODO))
                .thenReturn(java.util.List.of());
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = taskService.createFromToolIdempotently("설계서 작성", assignee, null, meeting);

        assertThat(result.created()).isTrue();
        assertThat(result.task().meetingId()).isEqualTo(11L);
        verify(taskRepository).save(any(Task.class));
    }

    @Test void rejectsSentenceTitleBeforeResolvingAssigneeOrSaving() {
        // 직접 서비스 호출도 담당자 조회보다 먼저 동일 제목 검증을 수행한다.
        assertThatThrownBy(() -> taskService.create(new CreateTaskRequest("배포 로그를 정리한다.", "없는 사람", null)))
                .isInstanceOf(com.meetodo.task.exception.InvalidTaskTitleException.class);
        org.mockito.Mockito.verifyNoInteractions(memberRepository,taskRepository);
    }

    @Test void toolServiceCannotBypassSentenceTitleValidation() {
        // 도구 래퍼를 거치지 않아도 서비스 저장 경계에서 문장형 제목을 거절한다.
        assertThatThrownBy(() -> taskService.createFromToolIdempotently("배포 로그를 정리한다.",null,null,meetingWithId(10L)))
                .isInstanceOf(com.meetodo.task.exception.InvalidTaskTitleException.class);
        org.mockito.Mockito.verifyNoInteractions(taskRepository);
    }

    @Test void reusesLegacyNominalTodoWithFinalPeriod() {
        // 과거 마침표 포함 제목을 변경하지 않고 새 명사형 입력과 TODO 중복으로 찾는다.
        Task existing=Task.create("배포 로그 정리.",null,null,meetingWithId(10L)).withId(3L,Instant.EPOCH);
        when(taskRepository.findAllByAssigneeIsNullAndStatusOrderByCreatedAtDesc(TaskStatus.TODO)).thenReturn(List.of(existing));
        var result=taskService.createFromToolIdempotently("배포 로그 정리",null,null,meetingWithId(11L));
        assertThat(result.created()).isFalse(); assertThat(result.task().id()).isEqualTo(3L);
        assertThat(existing.getTitle()).isEqualTo("배포 로그 정리.");
        verify(taskRepository,never()).save(any(Task.class));
    }

    private Meeting meetingWithId(long id) {
        Meeting meeting = Meeting.create("test meeting " + id, MeetingSource.API_TEXT);
        org.springframework.test.util.ReflectionTestUtils.setField(meeting, "id", id);
        return meeting;
    }
}
