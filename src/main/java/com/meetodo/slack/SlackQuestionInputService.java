package com.meetodo.slack;

import com.meetodo.rag.question.QuestionService;
import com.slack.api.app_backend.slash_commands.response.SlashCommandResponse;
import com.slack.api.methods.MethodsClient;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import com.slack.api.bolt.App;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackQuestionInputService {
    private static final Logger log = LoggerFactory.getLogger(SlackQuestionInputService.class);
    private final QuestionService questions;
    private final SlackQuestionRenderer renderer;
    private final Executor executor;
    public SlackQuestionInputService(QuestionService questions, MethodsClient client, Executor executor) {
        this.questions = questions; this.renderer = new SlackQuestionRenderer(client); this.executor = executor;
    }
    public void submit(String query, String user, Consumer<SlashCommandResponse> reply) {
        String normalized = query == null ? "" : query.replaceAll("(?U)^\\s+|\\s+$", "");
        if (normalized.isEmpty() || normalized.length() > 2000) { reply(reply, usage()); return; }
        try { executor.execute(() -> {
            SlashCommandResponse response;
            try { response = renderer.render(questions.ask(normalized, user, false)); }
            catch (com.meetodo.rag.question.RagQuestionException failure) {
                response = message("MEMBER_REQUIRED".equals(failure.code())
                        ? "등록된 팀원만 질문할 수 있어요. Slack 앱 홈의 내 정보 등록에서 본인을 등록해 주세요."
                        : "질문을 처리하지 못했어요. 질문 내용을 확인하고 다시 시도해 주세요.");
            } catch (Exception ignored) { response = message("질문 처리에 실패했어요. 잠시 후 다시 시도해 주세요."); }
            reply(reply, response);
        }); } catch (RuntimeException ignored) { reply(reply, message("현재 질문 요청이 많아요. 잠시 후 다시 시도해 주세요.")); }
    }
    // This handler is installed only when the optional meeting-input handler is absent.
    public void registerOnly(App app) {
        app.command("/meetodo", (request, context) -> {
            var ack = context.ack();
            String text = request.getPayload().getText();
            Consumer<SlashCommandResponse> respond = response -> {
                try { context.respond(response); } catch (Exception ignored) { log.warn("Slack question private response failed"); }
            };
            if (isQuestionCommand(text)) submit(text.substring(2), request.getPayload().getUserId(), respond);
            else reply(respond, message("회의록 입력 기능이 비활성화되어 있어요. 질문은 /meetodo 질문 <내용>으로 입력해 주세요."));
            return ack;
        });
    }
    static boolean isQuestionCommand(String text) {
        return text != null && text.startsWith("질문") && (text.length() == 2 || space(text.codePointAt(2)));
    }
    private static boolean space(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }
    static boolean emptyQuestion(String text) { return text == null || text.codePoints().allMatch(SlackQuestionInputService::space); }
    static SlashCommandResponse usage() { return message("사용법: /meetodo 질문 <내용> (질문은 1~2,000자)"); }
    static SlashCommandResponse message(String text) { return SlashCommandResponse.builder().responseType("ephemeral").text(text).build(); }
    private void reply(Consumer<SlashCommandResponse> responder, SlashCommandResponse response) {
        try { responder.accept(response); } catch (Exception ignored) { log.warn("Slack question private response failed"); }
    }
}
