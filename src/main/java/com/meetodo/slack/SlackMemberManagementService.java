package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.MemberManagementException;
import com.meetodo.member.MemberService;
import com.meetodo.member.dto.MemberRequest;
import com.meetodo.member.dto.MemberResponse;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.request.views.ViewsPushRequest;
import com.slack.api.methods.request.views.ViewsUpdateRequest;
import com.slack.api.model.view.ViewState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackMemberManagementService {
    public static final String MANAGE = "meetodo_manage_members";
    static final String NEW = "meetodo_member_new";
    static final String SELECT = "meetodo_member_select";
    static final String DEACTIVATE = "meetodo_member_deactivate";
    static final String DIRECTORY = "meetodo_member_directory";
    static final String FORM = "meetodo_member_form";
    private static final String VALUE = "value";
    private static final Logger log = LoggerFactory.getLogger(SlackMemberManagementService.class);
    private final MethodsClient client;
    private final MemberService members;
    private final AdminAuthorization admins;
    private final Clock clock;
    private final SlackHomeService homes;
    // Actor, operation and member ID are server-owned; client metadata never authorizes changes.
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(String actor, String operation, Long memberId, Instant expiresAt) { }

    public SlackMemberManagementService(MethodsClient client, MemberService members, AdminAuthorization admins,
                                        Clock clock, SlackHomeService homes) {
        this.client = client; this.members = members; this.admins = admins; this.clock = clock; this.homes = homes;
    }

    public void register(App app) {
        app.blockAction(MANAGE, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getUser() != null) open(payload.getTriggerId(), payload.getUser().getId());
            return ack;
        });
        app.blockAction(NEW, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null)
                select(payload.getTriggerId(), payload.getView().getId(), payload.getUser().getId(), null);
            return ack;
        });
        app.blockAction(SELECT, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null && payload.getActions() != null && !payload.getActions().isEmpty()) {
                var option = payload.getActions().getFirst().getSelectedOption();
                if (option != null) select(payload.getTriggerId(), payload.getView().getId(), payload.getUser().getId(), option.getValue());
            }
            return ack;
        });
        app.blockAction(DEACTIVATE, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) deactivate(payload.getView().getId(), payload.getUser().getId());
            return ack;
        });
        app.viewSubmission(FORM, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) {
                var state = payload.getView().getState();
                submit(payload.getView().getId(), payload.getUser().getId(), new MemberRequest(
                        field(state, "familyName", false), field(state, "givenName", false),
                        field(state, "team", false), field(state, "slackUserId", true)));
            }
            return ack;
        });
        for (String callback : List.of(DIRECTORY, FORM)) app.viewClosed(callback, (request, context) -> {
            var ack = context.ack(); var payload = request.getPayload();
            if (payload.getView() != null && payload.getUser() != null) close(payload.getView().getId(), payload.getUser().getId());
            return ack;
        });
    }

    public void open(String trigger, String actor) {
        try {
            admins.requireAdmin(actor); requireTrigger(trigger); capacity();
            var response = client.viewsOpen(ViewsOpenRequest.builder().triggerId(trigger).viewAsString(json(directory(members.findAll()))).build());
            if (!response.isOk() || response.getView() == null) throw new IllegalStateException();
            remember(response.getView().getId(), actor, DIRECTORY, null);
        } catch (Exception failure) { failure(actor, failure); }
    }

    public void select(String trigger, String viewId, String actor, String memberId) {
        if (origin(viewId, actor, false, DIRECTORY) == null) return;
        try {
            admins.requireAdmin(actor); requireTrigger(trigger); capacity();
            MemberResponse member = memberId == null ? null : members.findAll().stream()
                    .filter(value -> value.id().equals(Long.valueOf(memberId))).findFirst().orElseThrow();
            var response = client.viewsPush(ViewsPushRequest.builder().triggerId(trigger).viewAsString(json(form(member))).build());
            if (!response.isOk() || response.getView() == null) throw new IllegalStateException();
            remember(response.getView().getId(), actor, FORM, member == null ? null : member.id());
        } catch (Exception failure) { failure(actor, failure); }
    }

    public void submit(String viewId, String actor, MemberRequest request) {
        Pending origin = origin(viewId, actor, true, FORM);
        if (origin == null) return;
        try {
            admins.requireAdmin(actor); validate(request);
            if (origin.memberId() == null) members.create(request, actor);
            else members.update(origin.memberId(), request, actor);
            homes.publish(actor, origin.memberId() == null ? "팀원을 등록했어요." : "팀원 정보를 수정했어요.");
        } catch (Exception failure) { failure(actor, failure); }
    }

    public void deactivate(String viewId, String actor) {
        Pending origin = origin(viewId, actor, true, FORM);
        if (origin == null || origin.memberId() == null) return;
        try {
            admins.requireAdmin(actor); members.deactivate(origin.memberId(), actor);
            homes.publish(actor, "팀원을 비활성화했어요.");
            try {
                var response = client.viewsUpdate(ViewsUpdateRequest.builder().viewId(viewId).viewAsString(json(Map.of(
                        "type", "modal", "title", plain("팀원 관리"), "close", plain("닫기"),
                        "blocks", List.of(section("팀원을 비활성화했어요. 기존 업무와 회의 기록은 유지됩니다."))))).build());
                if (response == null || !response.isOk()) log.warn("Slack member modal refresh failed: INTERNAL_ERROR");
            } catch (Exception ignored) { log.warn("Slack member modal refresh failed: INTERNAL_ERROR"); }
        } catch (Exception failure) { failure(actor, failure); }
    }

    public void close(String viewId, String actor) {
        synchronized (pending) {
            Pending origin = pending.get(viewId);
            if (origin != null && origin.actor().equals(actor)) pending.remove(viewId);
        }
    }

    private Map<String, Object> directory(List<MemberResponse> all) {
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(section("수정할 팀원을 선택하거나 새 팀원을 등록하세요. 비활성 팀원은 기존 기록을 유지하며 다시 활성화하지 않습니다."));
        blocks.add(Map.of("type", "actions", "elements", List.of(Map.of("type", "button", "action_id", NEW, "text", plain("새 팀원 등록")))));
        if (!all.isEmpty()) blocks.add(Map.of("type", "section", "text", plain("팀원 선택"), "accessory", Map.of(
                "type", "static_select", "action_id", SELECT, "placeholder", plain("팀원 선택"),
                "options", all.stream().limit(100).map(member -> Map.of("text", plain(shortText(member.name(), 22) + " · "
                        + shortText(member.team(), 20) + " · " + shortText(member.slackUserId(), 17) + (member.active() ? "" : " (비활성)")),
                        "value", member.id().toString())).toList())));
        // shortcut: static_select exposes the first 100 members, add paged selection when the team grows beyond 100.
        if (all.size() > 100) blocks.add(section("현재 목록은 처음 100명만 표시합니다. 나머지 팀원은 관리자 API에서 관리하세요."));
        return Map.of("type", "modal", "callback_id", DIRECTORY, "notify_on_close", true,
                "title", plain("팀원 관리"), "close", plain("닫기"), "blocks", blocks);
    }

    private Map<String, Object> form(MemberResponse member) {
        var blocks = new ArrayList<Map<String, Object>>();
        blocks.add(input("familyName", "성", member == null ? null : member.familyName(), false));
        blocks.add(input("givenName", "이름 (성 제외)", member == null ? null : member.givenName(), false));
        blocks.add(input("team", "팀", member == null ? null : member.team(), false));
        blocks.add(input("slackUserId", "Slack 사용자", member == null ? null : member.slackUserId(), true));
        if (member != null && member.active()) blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
                "type", "button", "action_id", DEACTIVATE, "style", "danger", "text", plain("팀원 비활성화"),
                "confirm", Map.of("title", plain("비활성화 확인"), "text", plain("이 팀원을 비활성화할까요? 기존 업무와 회의 기록은 유지됩니다."),
                        "confirm", plain("비활성화"), "deny", plain("취소"), "style", "danger")))));
        if (member != null && !member.active()) blocks.add(section("비활성 팀원입니다. 정보를 수정해도 다시 활성화되지 않습니다."));
        return Map.of("type", "modal", "callback_id", FORM, "notify_on_close", true, "title", plain(member == null ? "새 팀원 등록" : "팀원 수정"),
                "submit", plain("저장"), "close", plain("취소"), "blocks", blocks);
    }

    private Map<String, Object> input(String field, String label, String initial, boolean user) {
        var element = new HashMap<String, Object>(); element.put("type", user ? "users_select" : "plain_text_input"); element.put("action_id", VALUE);
        if (!user) { element.put("min_length", 1); element.put("max_length", 80); }
        if (initial != null && !initial.isEmpty()) element.put(user ? "initial_user" : "initial_value", initial);
        return Map.of("type", "input", "block_id", field, "label", plain(label), "element", element);
    }
    private String field(ViewState state, String blockId, boolean user) {
        if (state == null || state.getValues() == null || state.getValues().get(blockId) == null) return null;
        var value = state.getValues().get(blockId).get(VALUE);
        if (value == null || !(user ? "users_select" : "plain_text_input").equals(value.getType())) return null;
        return user ? value.getSelectedUser() : value.getValue();
    }
    private void validate(MemberRequest request) {
        if (request == null) throw new IllegalArgumentException();
        for (String value : new String[]{request.familyName(), request.givenName(), request.team(), request.slackUserId()})
            if (value == null || value.isBlank() || value.length() > 80) throw new IllegalArgumentException();
        if (!request.slackUserId().matches("[UW][A-Z0-9]{1,79}")) throw new IllegalArgumentException();
    }
    private Pending origin(String id, String actor, boolean consume, String operation) {
        synchronized (pending) {
            expire(); Pending origin = pending.get(id);
            if (origin == null || !origin.actor().equals(actor) || !origin.operation().equals(operation)) return null;
            if (consume) pending.remove(id);
            return origin;
        }
    }
    private void remember(String id, String actor, String operation, Long memberId) {
        if (id == null || id.isBlank()) throw new IllegalStateException();
        synchronized (pending) { capacity(); pending.put(id, new Pending(actor, operation, memberId, clock.instant().plusSeconds(1800))); }
    }
    private void capacity() { synchronized (pending) { expire(); if (pending.size() >= 1000) throw new IllegalStateException(); } }
    private void expire() { pending.values().removeIf(value -> !clock.instant().isBefore(value.expiresAt())); }
    private void requireTrigger(String trigger) { if (trigger == null || trigger.isBlank()) throw new IllegalArgumentException(); }
    private String json(Object value) { return new GsonBuilder().disableHtmlEscaping().create().toJson(value); }
    private Map<String, String> plain(String value) { return Map.of("type", "plain_text", "text", value); }
    private Map<String, Object> section(String value) { return Map.of("type", "section", "text", plain(value)); }
    private String shortText(String value, int max) { return value == null || value.isBlank() ? "미설정" : value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private void failure(String actor, Exception failure) {
        log.warn("Slack member management failed: {}", failure instanceof MemberManagementException member ? member.getCode() : "INTERNAL_ERROR");
        homes.publish(actor, failure instanceof MemberManagementException ? failure.getMessage() : "팀원을 변경하지 못했어요. Home에서 다시 시도해 주세요.");
    }
}
