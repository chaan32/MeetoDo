package com.meetodo.slack;

import com.meetodo.rag.question.QuestionResponse;
import com.slack.api.app_backend.slash_commands.response.SlashCommandResponse;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatGetPermalinkRequest;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.PlainTextObject;
import com.slack.api.model.block.composition.MarkdownTextObject;
import java.net.URI;
import java.util.*;

public class SlackQuestionRenderer {
    private final MethodsClient client;
    public SlackQuestionRenderer(MethodsClient client) { this.client = client; }
    public SlashCommandResponse render(QuestionResponse response) {
        List<LayoutBlock> citations = new ArrayList<>();
        int citationLength = 0;
        for (var source : response.sources().stream().limit(10).toList()) {
            String label = "출처: 회의 #" + source.meetingId() + " (등록일 " + source.registeredDate() + ")";
            String link = permalink(source);
            String text = link == null ? label + " " + safe(source.apiPath()) : "<" + link + "|" + label + ">";
            if (citationLength + text.length() > 1400) break;
            citations.add(SectionBlock.builder().text(link == null ? PlainTextObject.builder().text(text).emoji(false).build()
                    : MarkdownTextObject.builder().text(text).verbatim(true).build()).build());
            citationLength += text.length();
        }
        if (citations.size() < response.sources().size()) {
            String omitted = "일부 출처는 길이 제한으로 생략됐어요.";
            citations.add(plain(omitted)); citationLength += omitted.length();
        }
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(plain(truncate(safe(response.answer()), 2950 - citationLength)));
        blocks.addAll(citations);
        return SlashCommandResponse.builder().responseType("ephemeral").text("MeetoDo 질문 결과").blocks(blocks).build();
    }
    private SectionBlock plain(String text) { return SectionBlock.builder().text(PlainTextObject.builder().text(text).emoji(false).build()).build(); }
    private String permalink(QuestionResponse.Source source) {
        if (source.channelId() == null || !source.channelId().matches("[CDG][A-Z0-9]{1,79}")
                || source.messageTs() == null || !source.messageTs().matches("[0-9]+\\.[0-9]+")) return null;
        try {
            var result = client.chatGetPermalink(ChatGetPermalinkRequest.builder().channel(source.channelId()).messageTs(source.messageTs()).build());
            if (!result.isOk() || result.getPermalink() == null) return null;
            String link = result.getPermalink(); URI uri = URI.create(link);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.endsWith(".slack.com")
                    && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().matches("/archives/[A-Z0-9]+/p[0-9]+") && link.length() <= 300 ? link : null;
        } catch (Exception ignored) { return null; }
    }
    private String safe(String text) {
        if (text == null || text.isBlank()) return "기록에 없습니다";
        return text.replaceAll("(?i)https?://[^\\s<>]+", "[URL 생략]")
                .replaceAll("(?i)\\b(?:xox[baprs]|xapp|sk)-[A-Za-z0-9_-]+", "[비밀값 숨김]")
                .replaceAll("(?i)Bearer\\s+[^\\s<>]+", "Bearer [비밀값 숨김]");
    }
    private String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit - 18;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "\n(긴 답변 일부 생략)";
    }
}
