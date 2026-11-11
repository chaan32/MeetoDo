package com.meetodo.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SlackPayloadRenderer {
    public Map<String, Object> render(NotificationMessage message) {
        String title = truncate(message.title(), 150);
        String summary = truncate(String.join("\n", message.summaryLines()), 3000);
        String footer = truncate(message.footer(), 3000);
        List<String> included = new ArrayList<>();
        boolean allFit = 3 + message.taskLines().size() <= 50
                && message.taskLines().stream().allMatch(line -> line.length() <= 3000)
                && fallback(title, summary, message.taskLines(), "", footer).length() <= 40000;
        if (allFit) {
            included.addAll(message.taskLines());
        } else {
            // shortcut: 한 메시지에 들어가는 접두 목록만 전송한다. 전체 목록은 회의 조회 API로 확인한다.
            for (String line : message.taskLines()) {
                int remaining = message.taskLines().size() - included.size() - 1;
                List<String> candidate = new ArrayList<>(included);
                candidate.add(line);
                String omitted = remaining == 0 ? "" : omission(message.meetingId(), remaining);
                if (line.length() > 3000 || 3 + candidate.size() + (remaining == 0 ? 0 : 1) > 50
                        || fallback(title, summary, candidate, omitted, footer).length() > 40000) break;
                included.add(line);
            }
        }
        int remaining = message.taskLines().size() - included.size();
        String omitted = remaining == 0 ? "" : omission(message.meetingId(), remaining);
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(block("header", title));
        blocks.add(block("section", summary));
        included.forEach(line -> blocks.add(block("section", line)));
        if (remaining > 0) blocks.add(block("section", omitted));
        blocks.add(Map.of("type", "context", "elements", List.of(plainText(footer))));
        return Map.of("text", fallback(title, summary, included, omitted, footer), "blocks", blocks,
                "link_names", false, "mrkdwn", false);
    }

    private Map<String, Object> block(String type, String text) {
        return Map.of("type", type, "text", plainText(text));
    }

    private Map<String, Object> plainText(String text) {
        return Map.of("type", "plain_text", "text", text, "emoji", true);
    }

    private String fallback(String title, String summary, List<String> tasks, String omitted, String footer) {
        List<String> lines = new ArrayList<>(List.of(title, summary));
        lines.addAll(tasks);
        if (!omitted.isEmpty()) lines.add(omitted);
        lines.add(footer);
        return String.join("\n", lines).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String omission(Long meetingId, int count) {
        return "외 " + count + "건은 GET /api/meetings/" + meetingId + "에서 확인";
    }

    private String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
