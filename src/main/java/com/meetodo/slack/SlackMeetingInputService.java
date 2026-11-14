package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.meeting.*;
import com.meetodo.meeting.dto.MeetingRequest;
import com.meetodo.notification.NotificationDeliveryException;
import com.slack.api.bolt.App;
import com.slack.api.app_backend.slash_commands.response.SlashCommandResponse;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostEphemeralRequest;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackMeetingInputService {
    static final String CALLBACK = "meetodo_meeting_input";
    static final String BLOCK = "meeting_text";
    static final String ACTION = "raw_text";
    private static final Logger log = LoggerFactory.getLogger(SlackMeetingInputService.class);
    private final MethodsClient client;
    private final MeetingApplicationService meetings;
    private final SlackBotService bot;
    private final Executor executor;
    private final Clock clock;
    private SlackQuestionInputService questions;
    // Keep the short-lived slash responder in memory only; never persist its credential-bearing response URL.
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(String channel, String user, Consumer<String> reply, Instant expiresAt) { }

    public SlackMeetingInputService(MethodsClient client, MeetingApplicationService meetings, SlackBotService bot,
                                    Executor executor, Clock clock) {
        this.client = client; this.meetings = meetings; this.bot = bot; this.executor = executor; this.clock = clock;
    }

    public void setQuestions(SlackQuestionInputService questions) { this.questions = questions; }

    public void register(App app) {
        app.command("/meetodo", (request, context) -> {
            var ack = context.ack();
            var payload = request.getPayload();
            if (SlackQuestionInputService.isQuestionCommand(payload.getText())) {
                Consumer<SlashCommandResponse> respond = response -> {
                    try { context.respond(response); } catch (Exception ignored) { log.warn("Slack question private response failed"); }
                };
                if (SlackQuestionInputService.emptyQuestion(payload.getText().substring(2))) respond.accept(SlackQuestionInputService.usage());
                else if (questions == null) respond.accept(SlackQuestionInputService.message("RAG 질문 기능이 비활성화되어 있어요."));
                else questions.submit(payload.getText().substring(2), payload.getUserId(), respond);
                return ack;
            }
            open(payload.getTriggerId(), payload.getChannelId(), payload.getUserId(), text -> {
                try { context.respond(SlashCommandResponse.builder().responseType("ephemeral").text(text).build()); }
                catch (Exception ignored) { log.warn("Slack input private response failed"); }
            });
            return ack;
        });
        app.viewSubmission(CALLBACK, (request, context) -> {
            var ack = context.ack();
            var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) {
                var view = payload.getView();
                String text = null;
                if (view.getState() != null && view.getState().getValues() != null) {
                    var block = view.getState().getValues().get(BLOCK);
                    if (block != null && block.get(ACTION) != null) text = block.get(ACTION).getValue();
                }
                submit(view.getId(), view.getPrivateMetadata(), payload.getUser().getId(), text);
            }
            return ack;
        });
        app.viewClosed(CALLBACK, (request, context) -> {
            var ack = context.ack();
            var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) close(payload.getView().getId(), payload.getUser().getId());
            return ack;
        });
    }

    public void open(String triggerId, String channelId, String userId, Consumer<String> reply) {
        if (triggerId == null || triggerId.isBlank() || !validChannel(channelId) || !validUser(userId)) {
            reply(reply, "명령을 실행한 채널에서 /meetodo를 다시 실행해 주세요."); return;
        }
        synchronized (pending) {
            expire();
            if (pending.size() >= 1000) { reply(reply, "현재 요청이 많아요. 잠시 후 다시 시도해 주세요."); return; }
        }
        try {
            Map<String,Object> view = Map.of("type", "modal", "callback_id", CALLBACK, "private_metadata", channelId,
                    "notify_on_close", true, "title", plain("회의록 입력"), "submit", plain("정리하기"), "close", plain("취소"),
                    "blocks", List.of(Map.of("type", "input", "block_id", BLOCK, "label", plain("회의록 (최대 3,000자)"),
                            "element", Map.of("type", "plain_text_input", "action_id", ACTION, "multiline", true, "min_length", 1, "max_length", 3000))));
            var response = client.viewsOpen(ViewsOpenRequest.builder().triggerId(triggerId)
                    .viewAsString(new GsonBuilder().disableHtmlEscaping().create().toJson(view)).build());
            if (!response.isOk() || response.getView() == null || response.getView().getId() == null) throw new IllegalStateException();
            synchronized (pending) {
                expire();
                if (pending.size() >= 1000) { reply(reply, "현재 요청이 많아요. 잠시 후 다시 시도해 주세요."); return; }
                pending.put(response.getView().getId(), new Pending(channelId, userId, reply, clock.instant().plusSeconds(30 * 60)));
            }
        } catch (Exception ignored) {
            log.warn("Slack meeting modal could not be opened");
            reply(reply, "회의록 입력 창을 열지 못했어요. /meetodo를 다시 실행해 주세요.");
        }
    }

    public void submit(String viewId, String channelId, String userId, String text) {
        Pending origin;
        synchronized (pending) {
            expire();
            origin = pending.get(viewId);
            if (origin != null && origin.channel().equals(channelId) && origin.user().equals(userId)) pending.remove(viewId);
            else origin = null;
        }
        if (origin == null) { ephemeral(channelId, userId, "입력 창이 만료됐어요. /meetodo로 다시 입력해 주세요."); return; }
        if (text == null || text.isBlank() || text.length() > 3000) {
            reply(origin.reply(), "회의록은 공백을 제외한 내용이 있어야 하고 3,000자 이하여야 해요. /meetodo로 다시 입력해 주세요."); return;
        }
        Pending accepted = origin;
        try { executor.execute(() -> process(accepted, text)); }
        catch (RuntimeException ignored) { reply(origin.reply(), "현재 요청이 많아요. 잠시 후 다시 시도해 주세요."); }
    }

    public void close(String viewId, String userId) {
        synchronized (pending) {
            Pending origin = pending.get(viewId);
            if (origin != null && origin.user().equals(userId)) pending.remove(viewId);
        }
    }

    private void process(Pending origin, String text) {
        var candidate = new AtomicReference<Meeting>();
        try {
            AtomicBoolean created = new AtomicBoolean();
            var response = meetings.submitFromSlack(new MeetingRequest(text), meeting -> {
                candidate.set(meeting);
                created.set(true);
                bot.startInput(meeting, origin.channel());
            }, discarded -> {
                created.set(false);
                bot.discardInputProgress(discarded);
            });
            if (!created.get()) notice(origin, candidate.get(), "이미 정리된 회의록입니다 (회의 " + response.meetingId() + ")");
        } catch (NotificationDeliveryException failure) {
            String code = com.meetodo.notification.NotificationStateService.safeCode(failure.getCode());
            log.warn("Slack progress post failed: code={}", code);
            notice(origin, candidate.get(), "not_in_channel".equals(code) || "channel_not_found".equals(code)
                    ? "/invite @MeetoDo 후 다시 시도해 주세요."
                    : "회의록 정리를 시작하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (MeetingProcessingException failure) {
            // The shared AFTER_COMMIT notifier replaces the original progress message with the safe failure result.
        } catch (Exception ignored) {
            log.warn("Slack meeting input processing failed");
            if (candidate.get() != null) bot.discardInputProgress(candidate.get());
            notice(origin, candidate.get(), "회의록 정리 기능을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private void notice(Pending origin, Meeting candidate, String text) {
        if (candidate != null && origin.channel().startsWith("D")
                && candidate.getSlackInputChannelId() != null && candidate.getSlackInputMessageTs() != null) {
            bot.showInputNotice(candidate.getSlackInputChannelId(), candidate.getSlackInputMessageTs(), text);
        } else reply(origin.reply(), text);
    }

    private void expire() { pending.values().removeIf(origin -> !clock.instant().isBefore(origin.expiresAt())); }
    private Map<String,String> plain(String text) { return Map.of("type", "plain_text", "text", text); }
    private void reply(Consumer<String> responder, String text) {
        try { responder.accept(text); }
        catch (Exception ignored) { log.warn("Slack input private response failed"); }
    }
    private void ephemeral(String channelId, String userId, String text) {
        if (!validChannel(channelId) || !validUser(userId)) return;
        try { client.chatPostEphemeral(ChatPostEphemeralRequest.builder().channel(channelId).user(userId).text(text).build()); }
        catch (Exception ignored) { log.warn("Slack input private response failed"); }
    }
    private boolean validChannel(String channel) { return channel != null && channel.matches("[CDG][A-Z0-9]{1,79}"); }
    private boolean validUser(String user) { return user != null && user.matches("[UW][A-Z0-9]{1,79}"); }
}
