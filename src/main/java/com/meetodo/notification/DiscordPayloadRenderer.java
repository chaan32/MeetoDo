package com.meetodo.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DiscordPayloadRenderer {
    public Map<String, Object> render(NotificationMessage message) {
        String title = truncate(message.title(), 256);
        String footer = truncate(message.footer(), 2048);
        int budget = Math.min(4096, 6000 - title.length() - footer.length());
        int total = message.taskLines().size();
        String summary = truncate(escape(String.join("\n", message.summaryLines())),
                budget - (total == 0 ? 0 : message.omission(total).length() + 2));
        List<String> taskLines = message.taskLines().stream().map(this::escape).toList();
        List<String> included = new ArrayList<>();
        if (description(summary, taskLines, "").length() <= budget) {
            included.addAll(taskLines);
        } else {
            // shortcut: 한 임베드에 들어가는 접두 목록만 전송한다. 전체 목록은 메시지 유형에 맞는 조회 API로 확인한다.
            for (String line : taskLines) {
                List<String> candidate = new ArrayList<>(included);
                candidate.add(line);
                int remaining = total - candidate.size();
                String omitted = remaining == 0 ? "" : message.omission(remaining);
                if (description(summary, candidate, omitted).length() > budget) break;
                included.add(line);
            }
        }
        int remaining = total - included.size();
        String omitted = remaining == 0 ? "" : message.omission(remaining);
        Map<String, Object> embed = Map.of("title", title, "description", description(summary, included, omitted),
                "footer", Map.of("text", footer));
        return Map.of("embeds", List.of(embed), "allowed_mentions", Map.of("parse", List.of()));
    }

    private String description(String summary, List<String> tasks, String omitted) {
        List<String> lines = new ArrayList<>(tasks);
        if (!omitted.isEmpty()) lines.add(omitted);
        return summary + (lines.isEmpty() ? "" : "\n\n" + String.join("\n", lines));
    }

    private String escape(String text) {
        return text.replaceAll("([\\\\`*_~|>\\[\\]()#])", "\\\\$1");
    }

    private String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
