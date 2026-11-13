package com.meetodo.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import com.meetodo.member.Member;
import com.meetodo.notification.*;
import com.meetodo.task.dto.TaskResponse;
import com.meetodo.tools.DateResolver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TaskDateWarningTests {
    private Task unresolved() {
        Task task = Task.create("API 검토", null, null, null, new UnresolvedAssignee("AMBIGUOUS", "지영", null,
                List.of(new UnresolvedAssignee.Candidate(2L, "이지영", "백엔드팀")), 0)).withId(1L, Instant.EPOCH);
        task.setDueDateWarning(DateResolver.UNKNOWN_WARNING);
        return task;
    }

    @Test void taskApiKeepsAssigneeAndDateWarningsSeparate() {
        // 담당자 경고와 날짜 경고를 함께 보여주면서 날짜 경고를 별도 필드로 제공한다.
        var response = TaskResponse.from(unresolved());
        assertThat(response.warning()).contains("동명이인", DateResolver.UNKNOWN_WARNING);
        assertThat(response.dueDateWarning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
    }

    @Test void assignmentClearsOnlyAssigneeWarning() {
        // 사람을 확정해도 미해결 마감 표현 경고는 사라지지 않는다.
        Task task = unresolved();
        task.assign(new Member("이", "지영", "백엔드팀", "UOWNER1"), "UADMIN1", Instant.EPOCH);
        var response = TaskResponse.from(task);
        assertThat(response.warning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
        assertThat(response.unresolvedAssignee()).isNull();
        assertThat(task.getUnresolvedAssignee()).isNotNull();
    }

    @Test void meetingApiPreservesDateWarningAfterAssignment() {
        // 과거 회의의 미정 스냅샷보다 현재 배정 상태를 우선하며 날짜 경고는 유지한다.
        Task task = unresolved();
        var detail = task.getUnresolvedAssignee();
        task.assign(new Member("이", "지영", "백엔드팀", "UOWNER1"), "UADMIN1", Instant.EPOCH);
        var response = MeetingTaskResponse.from(task, "CREATED", 1, detail.warning(), detail.reason(), detail);
        assertThat(response.warning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
        assertThat(response.dueDateWarning()).isEqualTo(DateResolver.UNKNOWN_WARNING);
        assertThat(response.unresolvedAssignee()).isNull();
    }

    @Test void slackWebhookJsonIncludesDateWarning() throws Exception {
        // Slack 웹훅 JSON도 날짜 확인 필요 경고를 표시한다.
        var payload = new SlackPayloadRenderer().render(message());
        assertThat(new ObjectMapper().writeValueAsString(payload)).contains(DateResolver.UNKNOWN_WARNING);
    }

    @Test void discordWebhookJsonIncludesDateWarning() throws Exception {
        // Discord embed JSON도 담당자와 독립된 날짜 경고를 표시한다.
        var payload = new DiscordPayloadRenderer().render(message());
        assertThat(new ObjectMapper().writeValueAsString(payload)).contains(DateResolver.UNKNOWN_WARNING);
    }

    private NotificationMessage message() {
        var item = MeetingTaskResponse.from(unresolved(), "CREATED", 1, null, null);
        var response = new MeetingResponse(1L, null, com.meetodo.meeting.MeetingStatus.DONE, "", List.of(item),
                1, 10L, 2, 1, 0, NotificationStatus.SKIPPED, null, null, null);
        return new NotificationMessageFactory().from(response);
    }
}
