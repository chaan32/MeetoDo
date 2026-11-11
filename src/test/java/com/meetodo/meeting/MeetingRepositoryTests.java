package com.meetodo.meeting;

import java.time.Instant;
import java.util.List;

import com.meetodo.task.Task;
import com.meetodo.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class MeetingRepositoryTests {

    @Autowired MeetingRepository meetings;
    @Autowired ToolCallLogRepository logs;
    @Autowired TaskRepository tasks;

    // 검증: savesMeetingWithPendingStatusAndCanTransitionThroughProcessingToDone 시나리오의 기대 동작을 확인한다.
    @Test
    void savesMeetingWithPendingStatusAndCanTransitionThroughProcessingToDone() {
        Meeting meeting = meetings.save(Meeting.create("회의록", MeetingSource.API_TEXT));

        assertThat(meetings.findById(meeting.getId()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.PENDING);

        meeting.startProcessing(Instant.parse("2026-11-11T00:00:00Z"));
        meeting.complete("요약", Instant.parse("2026-11-11T00:00:02Z"), 2, 2, 1);
        meetings.flush();

        Meeting saved = meetings.findById(meeting.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(saved.getProcessingTimeMs()).isEqualTo(2000L);
        assertThat(saved.getLlmCallCount()).isEqualTo(2);
        assertThat(saved.getToolCallCount()).isEqualTo(2);
        assertThat(saved.getValidationFailureCount()).isEqualTo(1);
    }

    // 검증: recordsFailureAndRejectsInvalidStateTransition 시나리오의 기대 동작을 확인한다.
    @Test
    void recordsFailureAndRejectsInvalidStateTransition() {
        Meeting meeting = meetings.save(Meeting.create("회의록", MeetingSource.API_TEXT));
        meeting.startProcessing(Instant.parse("2026-11-11T00:00:00Z"));
        meeting.fail(Instant.parse("2026-11-11T00:00:01Z"), 1, 1, 0);
        meetings.flush();

        assertThat(meetings.findById(meeting.getId()).orElseThrow().getStatus())
                .isEqualTo(MeetingStatus.FAILED);
        assertThatThrownBy(() -> meeting.complete("늦은 완료", Instant.parse("2026-11-11T00:00:02Z"), 2, 1, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    // 검증: storesToolLogsAndReadsThemByCallOrder 시나리오의 기대 동작을 확인한다.
    @Test
    void storesToolLogsAndReadsThemByCallOrder() {
        Meeting meeting = meetings.save(Meeting.create("회의록", MeetingSource.API_TEXT));
        logs.save(ToolCallLog.success(meeting, "createTask", "{}", "done", 2, 9));
        logs.save(ToolCallLog.failure(meeting, "findMember", "{}", "MEMBER_NOT_FOUND", "not found", 1, 3));

        List<ToolCallLog> result = logs.findAllByMeetingIdOrderByCallOrderAsc(meeting.getId());

        assertThat(result).extracting(ToolCallLog::getCallOrder).containsExactly(3, 9);
        assertThat(result.getFirst().getSuccess()).isFalse();
        assertThat(result.getLast().getSuccess()).isTrue();
    }

    // 검증: keepsTaskMeetingIdInApiWhileTaskUsesMeetingRelation 시나리오의 기대 동작을 확인한다.
    @Test
    void keepsTaskMeetingIdInApiWhileTaskUsesMeetingRelation() {
        Meeting meeting = meetings.save(Meeting.create("회의록", MeetingSource.API_TEXT));
        Task task = tasks.save(Task.create("할 일", null, null, meeting));

        assertThat(tasks.findById(task.getId()).orElseThrow().getMeetingId()).isEqualTo(meeting.getId());
    }

    // 검증: storesTaskMeetingAsJpaRelationWhileExposingItsId 시나리오의 기대 동작을 확인한다.
    @Test
    void storesTaskMeetingAsJpaRelationWhileExposingItsId() {
        Meeting meeting = meetings.save(Meeting.create("연관관계 확인", MeetingSource.API_TEXT));
        Task task = tasks.save(Task.create("관계 작업", null, null, meeting));

        Task saved = tasks.findById(task.getId()).orElseThrow();
        assertThat(saved.getMeeting()).isNotNull();
        assertThat(saved.getMeeting().getId()).isEqualTo(meeting.getId());
        assertThat(saved.getMeetingId()).isEqualTo(meeting.getId());
    }
}
