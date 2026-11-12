package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DigestPayloadRendererTests {
    ObjectMapper mapper = new ObjectMapper();

    // 회의 ID가 없는 다이제스트의 생략 안내는 할 일 조회 API를 가리킨다.
    @Test void slackDigestTruncationLinksToTasksInsteadOfNullMeeting() {
        var payload = new SlackPayloadRenderer().render(largeDigest());
        var json = mapper.valueToTree(payload);
        assertThat(json.path("text").asText()).contains("외 54건은 GET /api/tasks에서 확인").doesNotContain("meetings/null");
        assertThat(json.path("blocks").size()).isLessThanOrEqualTo(50);
    }

    // Discord도 임베드 한 건 안에서 할 일 접두 목록과 정확한 생략 안내를 제공한다.
    @Test void discordDigestTruncationLinksToTasksInsteadOfNullMeeting() {
        var json = mapper.valueToTree(new DiscordPayloadRenderer().render(largeDigest()));
        assertThat(json.path("embeds").size()).isEqualTo(1);
        assertThat(json.at("/embeds/0/description").asText()).contains("외 ", "GET /api/tasks에서 확인").doesNotContain("meetings/null");
        assertThat(json.at("/embeds/0/description").asText().length()).isLessThanOrEqualTo(4096);
        assertThat(json.at("/allowed_mentions/parse").size()).isZero();
    }

    // 다이제스트의 내용도 Slack 일반 텍스트 블록으로 변환해 실제 멘션을 차단한다.
    @Test void slackDigestJsonContainsOwnerBucketsAndWarning() {
        var json = mapper.valueToTree(new SlackPayloadRenderer().render(example()));
        assertThat(json.at("/blocks/0/text/text").asText()).contains("2026-11-12");
        assertThat(json.at("/blocks/2/text/type").asText()).isEqualTo("plain_text");
        assertThat(json.at("/blocks/2/text/text").asText()).contains("김민수(백엔드팀)", "⏰ 마감 지남");
        assertThat(json.path("text").asText()).contains("동명이인 확인 필요", "마감일 없는 TODO 1건");
        assertThat(json.path("mrkdwn").asBoolean()).isFalse();
    }

    // Discord 다이제스트는 임베드와 멘션 비활성 설정을 기존 경로로 만든다.
    @Test void discordDigestJsonContainsOwnerBucketsAndWarning() {
        var json = mapper.valueToTree(new DiscordPayloadRenderer().render(example()));
        assertThat(json.path("embeds").size()).isEqualTo(1);
        assertThat(json.at("/embeds/0/description").asText()).contains("김민수", "⏰ 마감 지남", "동명이인 확인 필요");
        assertThat(json.at("/embeds/0/footer/text").asText()).contains("마감일 없는 TODO 1건");
        assertThat(json.at("/allowed_mentions/parse").size()).isZero();
    }

    // 길이 제한으로 빠진 개수는 실제 포함된 접두 목록을 제외한 수와 같아야 한다.
    @Test void discordOmissionCountMatchesNumberOfIncludedTaskLines() {
        var json = mapper.valueToTree(new DiscordPayloadRenderer().render(largeDigest()));
        String description = json.at("/embeds/0/description").asText();
        long included = description.lines().filter(line -> line.startsWith("김민수")).count();
        assertThat(included).isBetween(1L, 99L);
        assertThat(description).contains("외 " + (100 - included) + "건은 GET /api/tasks에서 확인");
    }

    private NotificationMessage example() {
        return new NotificationMessage(null, "📋 마감 할 일 알림 (2026-11-12)", List.of("마감 지남 1건 · 오늘 마감 1건"),
                List.of("김민수(백엔드팀) · ⏰ 마감 지남 · 자료 준비 · 2026-11-11",
                        "담당자 미정 · 📌 오늘 마감 · 예약 확인 · ⚠️ 동명이인 확인 필요: 지영"),
                "마감일 없는 TODO 1건", NotificationMessage.Type.DAILY_DIGEST);
    }

    private NotificationMessage largeDigest() {
        return new NotificationMessage(null, "📋 마감 할 일 알림 (2026-11-12)", List.of("마감 지남 100건"),
                IntStream.range(0,100).mapToObj(i -> "김민수(백엔드팀) · ⏰ " + i + " " + "가".repeat(250)).toList(),
                "마감일 없는 TODO 0건", NotificationMessage.Type.DAILY_DIGEST);
    }
}
