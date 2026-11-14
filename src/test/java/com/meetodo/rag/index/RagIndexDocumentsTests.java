package com.meetodo.rag.index;

import com.meetodo.meeting.*;
import com.meetodo.member.Member;
import com.meetodo.rag.config.RagProperties;
import com.meetodo.task.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagIndexDocumentsTests {
    MeetingRepository meetings = mock(MeetingRepository.class);
    TaskRepository tasks = mock(TaskRepository.class);
    TaskMentionRepository mentions = mock(TaskMentionRepository.class);
    RagIndexDocuments documents;
    @BeforeEach void setup() {
        documents = new RagIndexDocuments(meetings, tasks, mentions, new RagProperties(new MockEnvironment()));
    }
    Meeting done(Long id, String text) {
        var meeting = Meeting.create(text, MeetingSource.API_TEXT);
        ReflectionTestUtils.setField(meeting, "id", id);
        ReflectionTestUtils.setField(meeting, "createdAt", Instant.parse("2026-11-14T00:00:00Z"));
        meeting.startProcessing(Instant.parse("2026-11-14T00:00:00Z"));
        meeting.complete("완료", Instant.parse("2026-11-14T00:00:01Z"), 1, 0, 0);
        when(meetings.findById(id)).thenReturn(Optional.of(meeting));
        return meeting;
    }
    @Test void preservesShortTranscriptAndServerMetadata() {
        // 짧은 회의록도 버리지 않고 서버의 회의 ID·등록일로 출처를 구성한다.
        done(1L, "결제 검토");
        var found = documents.forMeeting(1L);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getText()).isEqualTo("결제 검토");
        assertThat(found.getFirst().getMetadata()).containsEntry("meetingId", 1L).containsEntry("kind", "MEETING");
        assertThat(found.getFirst().getMetadata()).containsEntry("registeredAt", "2026-11-14T00:00:00Z");
    }
    @Test void includesLinkedTaskButExcludesDeletedTask() {
        // 회의에 연결된 업무만 별도 문서로 만들고 삭제된 업무는 제외한다.
        var meeting = done(1L, "회의실 예약 담당자를 확인한다.");
        var member = new Member("이", "지영", "백엔드팀", "UOWNER");
        var task = Task.create("회의실 예약 확인", member, LocalDate.of(2026, 11, 20), meeting);
        ReflectionTestUtils.setField(task, "id", 2L);
        var deleted = Task.create("삭제 업무", null, null, meeting); deleted.softDelete();
        ReflectionTestUtils.setField(deleted, "id", 3L);
        when(tasks.findAllByMeeting_IdOrderByCreatedAtDesc(1L)).thenReturn(List.of(task, deleted));
        assertThat(documents.forMeeting(1L)).hasSize(2).anySatisfy(document -> {
            assertThat(document.getMetadata()).containsEntry("taskId", 2L);
            assertThat(document.getText()).contains("회의실 예약 확인", "이지영", "백엔드팀", "2026-11-20");
        });
    }
    @Test void skipsMeetingsThatHaveNotFinishedSuccessfully() {
        // 처리 중 회의는 아직 확정된 기록이 아니므로 색인하지 않는다.
        var meeting = Meeting.create("처리 중 회의", MeetingSource.API_TEXT);
        ReflectionTestUtils.setField(meeting, "id", 1L);
        when(meetings.findById(1L)).thenReturn(Optional.of(meeting));
        assertThat(documents.forMeeting(1L)).isEmpty();
    }
    @Test void deterministicIdsAllowReindexWithoutDuplicatingDocuments() {
        // 같은 회의·청크는 재색인해도 같은 UUID를 사용한다.
        done(1L, "로그인 API 오류 수정 업무를 확인한다.");
        var first = documents.forMeeting(1L);
        assertThat(first).isNotEmpty();
        assertThat(documents.forMeeting(1L)).extracting(d -> d.getId()).containsExactlyElementsOf(first.stream().map(d -> d.getId()).toList());
        assertThatCode(() -> java.util.UUID.fromString(first.getFirst().getId())).doesNotThrowAnyException();
    }
    @Test void chunksNeverMixDifferentMeetings() {
        // 회의별로 분할하므로 다른 회의 원문은 한 출처에 섞이지 않는다.
        done(1L, "첫회의 결제 API 업무"); done(2L, "두번째회의 배포 점검 업무");
        assertThat(documents.forMeeting(1L)).allSatisfy(d -> assertThat(d.getText()).doesNotContain("두번째회의"));
        assertThat(documents.forMeeting(1L)).isNotEmpty();
    }
    @Test void sourceSnapshotRejectsDocumentIdFromDifferentMeeting() {
        // 검색 메타데이터의 회의 ID와 실제 문서 ID가 다른 조합은 근거로 인정하지 않는다.
        done(1L, "첫번째회의"); done(2L, "두번째회의");
        String id = documents.forMeeting(1L).getFirst().getId();
        assertThat(documents.sourceSnapshot(id, 2L)).isEmpty();
        assertThat(documents.sourceSnapshot(id, 1L)).isPresent();
    }
    @Test void standaloneTasksDoNotEnterMeetingIndex() {
        // 최신 승인 범위는 MeetoDo 처리 회의에 연결된 자료뿐이며 직접 등록 업무는 제외한다.
        when(tasks.findById(10L)).thenReturn(Optional.of(Task.create("직접 등록", null, null)));
        assertThat(documents.meetingIdsForTask(10L)).isEmpty();
    }
}
