package com.meetodo.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadRendererTests {
    // Slack은 사용자 입력을 plain_text로 보내 멘션과 마크업을 실행하지 않는다.
    @Test void slackUsesPlainTextAndDisablesLinkNames() {
        Map<String, Object> payload = new SlackPayloadRenderer().render(message(List.of("<!channel> <@U123> *제목*")));
        assertThat(payload.get("link_names")).isEqualTo(false);
        for (Map<String, Object> block : blocks(payload)) {
            if (block.containsKey("text")) assertThat(((Map<?, ?>) block.get("text")).get("type")).isEqualTo("plain_text");
            if (block.containsKey("elements")) assertThat(((Map<?, ?>) ((List<?>) block.get("elements")).getFirst()).get("type")).isEqualTo("plain_text");
        }
        assertThat((String) payload.get("text")).doesNotContain("<!channel>", "<@U123>");
    }

    // 블록 한도를 초과하는 목록은 최대 접두 목록과 정확한 생략 수를 남긴다.
    @Test void slackKeepsMaximalPrefixWithinFiftyBlocks() {
        List<String> lines = lines(1000, 20);
        Map<String, Object> payload = new SlackPayloadRenderer().render(message(lines));
        List<Map<String, Object>> blocks = blocks(payload);
        assertThat(blocks).hasSize(50);
        String text = slackSections(payload);
        assertThat(text).contains(lines.get(45)).doesNotContain(lines.get(46));
        assertThat(text).contains("외 954건은 GET /api/meetings/42에서 확인");
        assertThat(((String) payload.get("text")).length()).isLessThanOrEqualTo(40000);
    }

    // 3000자 경계까지는 한 줄 전체를 보내고 다음 긴 줄은 생략한다.
    @Test void slackPreservesEntireLineAtSectionBoundary() {
        String line = "가".repeat(3000);
        Map<String, Object> payload = new SlackPayloadRenderer().render(message(List.of(line, "나".repeat(3001), "마지막")));
        assertThat(slackSections(payload)).contains(line).doesNotContain("나", "마지막").contains("외 2건");
        for (Map<String, Object> block : blocks(payload)) {
            if (block.get("text") instanceof Map<?, ?> text) assertThat(((String) text.get("text")).length()).isLessThanOrEqualTo("header".equals(block.get("type")) ? 150 : 3000);
        }
    }

    // 첫 할 일조차 길면 0개를 표시하고 전체 건수를 생략 안내한다.
    @Test void slackCountsAllTasksWhenNoneFit() {
        Map<String, Object> payload = new SlackPayloadRenderer().render(message(List.of("가".repeat(3001), "짧은 줄")));
        assertThat(slackSections(payload)).contains("외 2건").doesNotContain("짧은 줄");
    }

    // 블록 수가 남아도 접근성용 fallback 전체 길이를 넘는 다음 줄은 생략한다.
    @Test void slackRespectsFallbackBudgetBeforeBlockLimit() {
        List<String> lines = lines(100, 2990);
        Map<String, Object> payload = new SlackPayloadRenderer().render(message(lines));
        String fallback = (String) payload.get("text");
        int included = 0;
        while (included < lines.size() && fallback.contains(lines.get(included))) included++;
        assertThat(included).isBetween(1, 45);
        assertThat(fallback.length()).isLessThanOrEqualTo(40000);
        assertThat(fallback.length() + lines.get(included).length()).isGreaterThan(40000);
        assertThat(fallback).contains("외 " + (100 - included) + "건");
    }

    // 생략 문구를 넣을 때만 초과하고 전체 작업은 맞으면 전부 보낸다.
    @Test void slackIncludesAllTasksWhenOmissionWouldUseMoreSpace() {
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < 13; i++) tasks.add("가".repeat(3000));
        tasks.add("나".repeat(979));
        tasks.add("끝");
        NotificationMessage message = new NotificationMessage(42L, "제", List.of("요"), tasks, "꼬");
        Map<String, Object> payload = new SlackPayloadRenderer().render(message);
        assertThat((String) payload.get("text")).hasSize(40000).contains("나".repeat(979), "끝").doesNotContain("외 ");
        assertThat(blocks(payload)).hasSize(18);
    }

    // Discord의 멘션 실행과 사용자 마크다운을 차단한다.
    @Test void discordDisablesMentionsAndEscapesMarkdown() {
        Map<String, Object> payload = new DiscordPayloadRenderer().render(message(List.of("@everyone **제목** [링크](https://example.com)")));
        assertThat(payload.get("allowed_mentions")).isEqualTo(Map.of("parse", List.of()));
        assertThat(description(payload)).contains("\\*\\*제목\\*\\*", "\\[링크\\]\\(");
    }

    // 최대 접두 목록의 다음 한 줄은 생략 안내를 포함한 4096자 예산을 넘는다.
    @Test void discordKeepsMaximalPrefixAndAccurateOmission() {
        List<String> lines = lines(1000, 100);
        Map<String, Object> payload = new DiscordPayloadRenderer().render(message(lines));
        String description = description(payload);
        int included = 0;
        while (included < lines.size() && description.contains(lines.get(included))) included++;
        assertThat(included).isGreaterThan(0);
        String omitted = "외 " + (1000 - included) + "건은 GET /api/meetings/42에서 확인";
        assertThat(description).endsWith(omitted);
        assertThat(description.length()).isLessThanOrEqualTo(4096);
        String next = description.substring(0, description.lastIndexOf(omitted)) + lines.get(included) + "\n외 " + (999 - included) + "건은 GET /api/meetings/42에서 확인";
        assertThat(next.length()).isGreaterThan(4096);
    }

    // 첫 항목이 너무 길면 다른 짧은 항목도 건너뛰지 않고 모두 생략한다.
    @Test void discordCountsAllTasksWhenNoneFit() {
        Map<String, Object> payload = new DiscordPayloadRenderer().render(message(List.of("가".repeat(5000), "짧은 줄")));
        assertThat(description(payload)).contains("외 2건").doesNotContain("짧은 줄", "가");
    }

    // 설명의 정확한 4096자 경계에서 마지막 할 일 한 줄을 훼손하지 않는다.
    @Test void discordPreservesEntireLineAtDescriptionBoundary() {
        String omission = "외 1건은 GET /api/meetings/42에서 확인";
        String line = "가".repeat(4096 - "요약\n\n".length() - ("\n" + omission).length());
        NotificationMessage message = new NotificationMessage(42L, "제목", List.of("요약"), List.of(line, "나".repeat(100)), "꼬리말");
        String description = description(new DiscordPayloadRenderer().render(message));
        assertThat(description).contains(line).endsWith(omission);
        assertThat(description.length()).isEqualTo(4096);
    }

    // 마지막 짧은 항목이 생략 문구보다 작으면 전체 목록을 우선한다.
    @Test void discordIncludesAllTasksWhenOmissionWouldUseMoreSpace() {
        NotificationMessage message = new NotificationMessage(42L, "제목", List.of("요".repeat(3890)), List.of("가".repeat(200), "끝"), "꼬리말");
        String description = description(new DiscordPayloadRenderer().render(message));
        assertThat(description).hasSize(4094).contains("가".repeat(200)).endsWith("끝").doesNotContain("외 ");
    }

    // 합성된 긴 제목·요약·꼬리말도 각 필드와 임베드 합계 제한을 지킨다.
    @Test void discordReservesFooterWithinTotalBudget() {
        NotificationMessage message = new NotificationMessage(42L, "제".repeat(500), List.of("요".repeat(5000)), List.of("할 일".repeat(1000)), "꼬".repeat(3000));
        Map<String, Object> payload = new DiscordPayloadRenderer().render(message);
        Map<String, Object> embed = embed(payload);
        String title = (String) embed.get("title");
        String footer = (String) ((Map<?, ?>) embed.get("footer")).get("text");
        assertThat(title.length()).isLessThanOrEqualTo(256);
        assertThat(footer.length()).isLessThanOrEqualTo(2048);
        assertThat(title.length() + footer.length() + description(payload).length()).isLessThanOrEqualTo(6000);
        assertThat(description(payload)).contains("외 1건");
    }

    private NotificationMessage message(List<String> lines) { return new NotificationMessage(42L, "회의 정리", List.of("신규 1건 · 기존 0건", "담당자 미정 0건"), lines, "회의 42 · 120ms"); }
    private List<String> lines(int count, int width) { return IntStream.range(0, count).mapToObj(i -> "항목%04d ".formatted(i) + "가".repeat(width)).toList(); }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> blocks(Map<String, Object> payload) { return (List<Map<String, Object>>) payload.get("blocks"); }
    private String slackSections(Map<String, Object> payload) {
        List<String> text = new ArrayList<>();
        for (Map<String, Object> block : blocks(payload)) if (block.get("text") instanceof Map<?, ?> item) text.add((String) item.get("text"));
        return String.join("\n", text);
    }
    @SuppressWarnings("unchecked") private Map<String, Object> embed(Map<String, Object> payload) { return (Map<String, Object>) ((List<?>) payload.get("embeds")).getFirst(); }
    private String description(Map<String, Object> payload) { return (String) embed(payload).get("description"); }
}
