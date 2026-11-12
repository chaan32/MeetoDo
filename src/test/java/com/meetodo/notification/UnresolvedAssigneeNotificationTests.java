package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.MeetingStatus;
import com.meetodo.meeting.dto.MeetingResponse;
import com.meetodo.meeting.dto.MeetingTaskResponse;
import com.meetodo.task.UnresolvedAssignee;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UnresolvedAssigneeNotificationTests {
    ObjectMapper mapper = new ObjectMapper();

    // 동명이인 경고가 두 플랫폼의 실제 JSON 본문에 이름과 후보 팀을 포함한다.
    @Test void ambiguousWarningIncludesNameAndCandidateTeamsInBothPlatforms() throws Exception {
        assertPayloads(new UnresolvedAssignee("AMBIGUOUS", "지영", null, List.of(
                new UnresolvedAssignee.Candidate(2L, "이지영", "백엔드팀"),
                new UnresolvedAssignee.Candidate(3L, "이지영", "디자인팀")), 0),
                "동명이인 확인 필요: \"지영\" (후보: 백엔드팀, 디자인팀)");
    }

    // 팀 불일치 경고에 요청 팀과 실제 등록 팀을 함께 표시한다.
    @Test void mismatchWarningIncludesRequestedAndRegisteredTeamsInBothPlatforms() throws Exception {
        assertPayloads(new UnresolvedAssignee("TEAM_MISMATCH", "해찬", "프론트엔드팀", List.of(
                new UnresolvedAssignee.Candidate(4L, "전해찬", "백엔드팀")), 0),
                "팀 불일치 확인 필요: \"프론트엔드팀 해찬\" (등록: 백엔드팀 전해찬)");
    }

    // 미등록 담당자 경고가 두 플랫폼에 요청 이름을 보존한다.
    @Test void notFoundWarningIncludesRequestedNameInBothPlatforms() throws Exception {
        assertPayloads(new UnresolvedAssignee("NOT_FOUND", "준호", null, List.of(), 0),
                "등록되지 않은 담당자: \"준호\"");
    }

    // 명시된 사람이 없는 미정 항목은 경고를 만들지 않는다.
    @Test void noneReasonDoesNotGenerateWarning() {
        NotificationMessage message = message(new UnresolvedAssignee("NONE", null, null, List.of(), 0));
        assertThat(message.taskLines().getFirst()).doesNotContain("⚠️");
    }

    // 사용자 입력은 Slack 일반 텍스트로, Discord에서는 멘션 해석 없이 출력한다.
    @Test void everyoneNameCannotEnablePlatformMentions() throws Exception {
        NotificationMessage message = message(new UnresolvedAssignee("NOT_FOUND", "@everyone <@123>", null, List.of(), 0));
        var discord = new DiscordPayloadRenderer().render(message);
        assertThat(mapper.valueToTree(discord).at("/allowed_mentions/parse").size()).isZero();
        var slack = mapper.valueToTree(new SlackPayloadRenderer().render(message));
        assertThat(slack.at("/blocks/2/text/type").asText()).isEqualTo("plain_text");
        assertThat(slack.path("mrkdwn").asBoolean()).isFalse();
        assertThat(slack.path("text").asText()).contains("&lt;@123&gt;");
    }

    // 긴 이름도 플랫폼 길이 제한 안에서 메시지 한 건으로 변환한다.
    @Test void longNameStillFitsSingleMessageLimits() throws Exception {
        NotificationMessage message = message(new UnresolvedAssignee("NOT_FOUND", "😀".repeat(500), null, List.of(), 0));
        var discord = mapper.valueToTree(new DiscordPayloadRenderer().render(message));
        assertThat(discord.at("/embeds/0/description").asText().length()).isLessThanOrEqualTo(4096);
        assertThat(discord.path("embeds").size()).isEqualTo(1);
        var slack = mapper.valueToTree(new SlackPayloadRenderer().render(message));
        assertThat(slack.path("text").asText().length()).isLessThanOrEqualTo(40000);
        assertThat(slack.path("blocks").size()).isLessThanOrEqualTo(50);
    }

    private void assertPayloads(UnresolvedAssignee detail, String expected) throws Exception {
        NotificationMessage message = message(detail);
        assertThat(message.taskLines().getFirst()).contains("⚠️ " + expected);
        var slack = mapper.valueToTree(new SlackPayloadRenderer().render(message));
        assertThat(slack.at("/blocks/2/text/text").asText()).contains(expected);
        var discord = mapper.valueToTree(new DiscordPayloadRenderer().render(message));
        assertThat(discord.at("/embeds/0/description").asText()).contains(expected.replace("(", "\\(").replace(")", "\\)"));
    }

    private NotificationMessage message(UnresolvedAssignee detail) {
        var task = new MeetingTaskResponse(1L, "로그인 API 오류 수정", null, null, null, "CREATED", 1,
                detail.warning(), detail.reason(), detail);
        return new NotificationMessageFactory().from(new MeetingResponse(42L, null, MeetingStatus.DONE, "", List.of(task),
                1, 120L, 1, 2, 0, null, null, null, null));
    }
}
