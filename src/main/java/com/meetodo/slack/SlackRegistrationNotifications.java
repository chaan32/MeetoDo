package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.registration.*;
import com.meetodo.notification.NotificationDeliveryException;
import com.meetodo.notification.NotificationStateService;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.chat.ChatUpdateRequest;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class SlackRegistrationNotifications {
    private static final Logger log = LoggerFactory.getLogger(SlackRegistrationNotifications.class);
    private final MethodsClient client;
    private final MemberRegistrationService registrations;
    private final AdminAuthorization admins;
    private final SlackRegistrationMessageRepository messages;
    private final TransactionTemplate transactions;
    private final SlackHomeService homes;

    public SlackRegistrationNotifications(MethodsClient client, MemberRegistrationService registrations,
            AdminAuthorization admins, SlackRegistrationMessageRepository messages,
            PlatformTransactionManager manager, SlackHomeService homes) {
        this.client = client; this.registrations = registrations; this.admins = admins;
        this.messages = messages; this.homes = homes; this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void changed(Long id) {
        RegistrationView request = registrations.getForNotification(id);
        List<SlackRegistrationMessage> stored = transactions.execute(status -> messages.findAllByRequestId(id));
        if (request.status() == RegistrationStatus.PENDING) {
            for (String admin : admins.ids()) {
                if (stored.stream().noneMatch(message -> message.getAdminSlackUserId().equals(admin))) pending(request, admin);
            }
            refreshHome();
            return;
        }
        for (SlackRegistrationMessage message : stored) {
            if (message.getStatus() == SlackRegistrationMessage.Status.SENT) update(request, message);
        }
        applicant(request);
        refreshHome();
    }

    private void refreshHome() {
        try { homes.refreshAll(); }
        catch (Exception ignored) { log.warn("Slack registration Home refresh failed: INTERNAL_ERROR"); }
    }

    private void pending(RegistrationView request, String admin) {
        String channel = null, timestamp = null, error = null;
        try {
            channel = open(admin);
            String name = request.familyName() + request.givenName() + "(" + request.team() + ")";
            String heading = "👤 팀원 등록 요청: " + name;
            var blocks = new ArrayList<Map<String, Object>>();
            blocks.add(plain(heading));
            String mention = SlackBotRenderer.mention(request.slackUserId());
            if (!mention.isEmpty()) blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", mention)));
            if (request.duplicateName()) blocks.add(plain("⚠️ 동명이인 주의: 같은 이름의 팀원이 있어요. 이름과 팀을 확인해 주세요."));
            blocks.add(Map.of("type", "actions", "elements", List.of(
                    button("승인", "meetodo_registration_approve", request.id()),
                    button("수정 후 승인", "meetodo_registration_edit", request.id()),
                    button("거절", "meetodo_registration_reject", request.id()))));
            timestamp = post(channel, SlackBotRenderer.escape(heading) + " " + mention, blocks);
        } catch (Exception exception) { error = code(exception); }
        var message = new SlackRegistrationMessage(request.id(), admin, channel, timestamp, error);
        if (persist(message) && error == null) {
            try {
                // Read after commit in a new transaction so a concurrent decision replaces stale pending buttons.
                RegistrationView latest = transactions.execute(status -> registrations.getForNotification(request.id()));
                if (latest.status() != RegistrationStatus.PENDING) update(latest, message);
            } catch (Exception ignored) { log.warn("Slack registration decision refresh failed: INTERNAL_ERROR"); }
        }
    }

    private void update(RegistrationView request, SlackRegistrationMessage message) {
        String error = null;
        String text = "처리됨(" + (request.status() == RegistrationStatus.APPROVED ? "승인" : "거절")
                + ", " + SlackBotRenderer.mention(request.processedBySlackUserId()) + ")";
        try {
            var response = client.chatUpdate(ChatUpdateRequest.builder().channel(message.getChannelId())
                    .ts(message.getMessageTs()).text(text).blocksAsString(json(List.of(Map.of("type", "section",
                            "text", Map.of("type", "mrkdwn", "text", text))))).parse("none").linkNames(false).build());
            if (!response.isOk()) throw failure(response.getError());
        } catch (Exception exception) { error = code(exception); }
        message.result(error); persist(message);
    }

    private void applicant(RegistrationView request) {
        String text = request.status() == RegistrationStatus.APPROVED ? "팀원으로 등록됐어요"
                : "팀원 등록 요청이 거절됐어요. 사유: " + Objects.toString(request.rejectionReason(), "");
        try { post(open(request.slackUserId()), SlackBotRenderer.escape(text), List.of(plain(text))); }
        catch (Exception exception) { log.warn("Slack registration applicant notification failed: {}", code(exception)); }
    }

    private String open(String user) throws Exception {
        if (SlackBotRenderer.mention(user).isEmpty()) throw failure("CONFIGURATION_ERROR");
        var response = client.conversationsOpen(ConversationsOpenRequest.builder().users(List.of(user)).build());
        if (!response.isOk()) throw failure(response.getError());
        if (response.getChannel() == null || response.getChannel().getId() == null) throw failure(null);
        return response.getChannel().getId();
    }

    private String post(String channel, String text, List<Map<String, Object>> blocks) throws Exception {
        var response = client.chatPostMessage(ChatPostMessageRequest.builder().channel(channel).text(bound(text))
                .blocksAsString(json(blocks)).parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).build());
        if (!response.isOk()) throw failure(response.getError());
        if (response.getTs() == null) throw failure(null);
        return response.getTs();
    }
    private boolean persist(SlackRegistrationMessage message) {
        if (message.getErrorCode() != null) log.warn("Slack registration notification failed: {}", message.getErrorCode());
        try {
            transactions.executeWithoutResult(status -> messages.save(message));
            return true;
        } catch (Exception ignored) {
            log.warn("Slack registration message persistence failed: INTERNAL_ERROR");
            return false;
        }
    }
    private static Map<String, Object> plain(String text) {
        return Map.of("type", "section", "text", Map.of("type", "plain_text", "text", bound(text), "emoji", true));
    }
    private static Map<String, Object> button(String text, String action, Long id) {
        return Map.of("type", "button", "text", Map.of("type", "plain_text", "text", text),
                "action_id", action, "value", id.toString());
    }
    private static String bound(String text) { return text.length() <= 3000 ? text : text.substring(0, 2999) + "…"; }
    private static String json(Object value) { return new GsonBuilder().disableHtmlEscaping().create().toJson(value); }
    private static NotificationDeliveryException failure(String error) {
        return new NotificationDeliveryException(NotificationStateService.safeCode(error));
    }
    private static String code(Exception exception) {
        return exception instanceof NotificationDeliveryException delivery
                ? NotificationStateService.safeCode(delivery.getCode()) : "INTERNAL_ERROR";
    }
}
