package com.meetodo.notification;

import com.meetodo.meeting.MeetingStatus;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageFactoryTests {
    // 서버의 할 일만 요약과 목록의 근거로 사용한다.
    @Test void buildsCountsAndTaskDetailsWithoutModelSummary() {
        MeetingResponse response = response(MeetingStatus.DONE, null, "비밀 회의록과 모델이 지어낸 요약", List.of(
                new MeetingTaskResponse(1L, "API 수정", "지영", LocalDate.of(2026, 11, 15), "백엔드팀", "CREATED", 1, null, null),
                new MeetingTaskResponse(2L, "테스트", null, null, null, "EXISTING", 3, "마감일 확인", "NOT_FOUND")));
        NotificationMessage message = new NotificationMessageFactory().from(response);
        assertThat(message.title()).isEqualTo("📝 회의 정리 완료 (할 일 2건)");
        assertThat(message.summaryLines()).hasSizeBetween(2, 3);
        assertThat(String.join("\n", message.summaryLines())).contains("새로 등록 1건", "기존 할 일 1건", "담당자 미정 1건").doesNotContain("비밀");
        assertThat(message.taskLines()).containsExactly(
                "🆕 지영(백엔드팀) · API 수정 · 2026-11-15",
                "♻️ 담당자 미정 · 테스트 · 마감일 없음 · 3번째 언급 · ⚠️ 마감일 확인");
        assertThat(message.footer()).contains("42", "120ms");
    }

    // 같은 할 일 묶음은 원본과 건수만 안내한다.
    @Test void duplicateShowsOriginalAndNoTaskLines() {
        MeetingResponse response = response(MeetingStatus.DONE, 7L, "source-summary", List.of(
                new MeetingTaskResponse(2L, "테스트", null, null, null, "EXISTING", 3, null, "NONE")));
        NotificationMessage message = new NotificationMessageFactory().from(response);
        assertThat(String.join("\n", message.summaryLines())).contains("7", "같은 할 일", "새로 등록 0건", "기존 할 일 1건", "담당자 미정 1건");
        assertThat(message.taskLines()).isEmpty();
        assertThat(message.toString()).doesNotContain("source-summary");
    }

    // 실패 응답은 모델 요약이나 공급자 오류를 포함하지 않는다.
    @Test void failedShowsOnlyGenericFailure() {
        MeetingResponse response = response(MeetingStatus.FAILED, null, "provider-secret", List.of());
        NotificationMessage message = new NotificationMessageFactory().from(response);
        assertThat(message.title()).contains("실패");
        assertThat(message.toString()).doesNotContain("provider-secret");
        assertThat(message.taskLines()).isEmpty();
    }

    // 할 일이 없는 회의도 0건으로 표시한다.
    @Test void emptyTasksProduceZeroCounts() {
        NotificationMessage message = new NotificationMessageFactory().from(response(MeetingStatus.DONE, null, "", List.of()));
        assertThat(message.title()).contains("0건");
        assertThat(String.join("\n", message.summaryLines())).contains("새로 등록 0건", "기존 할 일 0건", "담당자 미정 0건");
    }

    // 동명이인으로 미정인 경우 서버 경고를 그대로 표시한다.
    @Test void ambiguousAssigneePreservesSuppliedWarning() {
        NotificationMessage message = unassignedMessage("AMBIGUOUS", "동명이인이 있어 담당자 미정으로 등록했습니다.");
        assertThat(message.taskLines()).containsExactly("🆕 담당자 미정 · 테스트 · 마감일 없음 · ⚠️ 동명이인이 있어 담당자 미정으로 등록했습니다.");
        assertThat(message.toString()).doesNotContain("source-summary");
    }

    // 등록되지 않은 담당자는 미정 표시와 서버 경고를 유지한다.
    @Test void notFoundAssigneePreservesSuppliedWarning() {
        NotificationMessage message = unassignedMessage("NOT_FOUND", "등록된 팀원을 찾지 못했습니다.");
        assertThat(message.taskLines()).containsExactly("🆕 담당자 미정 · 테스트 · 마감일 없음 · ⚠️ 등록된 팀원을 찾지 못했습니다.");
        assertThat(message.toString()).doesNotContain("source-summary");
    }

    // 팀 불일치로 미정인 경우에도 이유를 바꾸거나 추측하지 않는다.
    @Test void teamMismatchAssigneePreservesSuppliedWarning() {
        NotificationMessage message = unassignedMessage("TEAM_MISMATCH", "회의록의 팀과 일치하는 팀원이 없습니다.");
        assertThat(message.taskLines()).containsExactly("🆕 담당자 미정 · 테스트 · 마감일 없음 · ⚠️ 회의록의 팀과 일치하는 팀원이 없습니다.");
        assertThat(message.toString()).doesNotContain("source-summary");
    }

    // 명시된 담당자가 없는 경우 임의 경고를 만들지 않는다.
    @Test void unspecifiedAssigneeDoesNotInventWarning() {
        NotificationMessage message = unassignedMessage("NONE", null);
        assertThat(message.taskLines()).containsExactly("🆕 담당자 미정 · 테스트 · 마감일 없음");
        assertThat(message.toString()).doesNotContain("source-summary", "⚠️");
    }

    private NotificationMessage unassignedMessage(String reason, String warning) {
        return new NotificationMessageFactory().from(response(MeetingStatus.DONE, null, "source-summary", List.of(
                new MeetingTaskResponse(1L, "테스트", null, null, null, "CREATED", 1, warning, reason))));
    }

    private MeetingResponse response(MeetingStatus status, Long duplicateOf, String summary, List<MeetingTaskResponse> tasks) {
        return new MeetingResponse(42L, duplicateOf, status, summary, tasks, 0, 120L, 0, 0, 0,
                null, null, null, null);
    }
}
