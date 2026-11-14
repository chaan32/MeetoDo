package com.meetodo.slack;

import com.google.gson.GsonBuilder;
import com.meetodo.member.AdminAuthorization;
import com.meetodo.member.MemberManagementException;
import com.meetodo.member.registration.*;
import com.slack.api.bolt.App;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.views.ViewsOpenRequest;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import com.slack.api.methods.request.conversations.ConversationsOpenRequest;
import com.slack.api.model.view.ViewState;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackMemberRegistrationService {
    public static final String REQUEST = "meetodo_request_membership";
    public static final String APPROVE = "meetodo_registration_approve";
    public static final String EDIT = "meetodo_registration_edit";
    public static final String REJECT = "meetodo_registration_reject";
    static final String FORM = "meetodo_registration_form";
    private static final Logger log = LoggerFactory.getLogger(SlackMemberRegistrationService.class);
    private final MethodsClient client;
    private final MemberRegistrationService registrations;
    private final AdminAuthorization admins;
    private final SlackHomeService homes;
    private final Clock clock;
    // Actor, request and operation are server-owned; client metadata never authorizes a mutation.
    private final Map<String, Pending> pending = new HashMap<>();
    private record Pending(String actor, Long requestId, boolean reject, List<String> teams, Instant expiresAt) { }

    public SlackMemberRegistrationService(MethodsClient client, MemberRegistrationService registrations,
            AdminAuthorization admins, SlackHomeService homes, Clock clock) {
        this.client=client; this.registrations=registrations; this.admins=admins; this.homes=homes; this.clock=clock;
    }

    public void register(App app) {
        for (String action : List.of(REQUEST, APPROVE, EDIT, REJECT)) app.blockAction(action, (request, context) -> {
            var ack=context.ack(); var payload=request.getPayload();
            if (payload.getUser() == null) return ack;
            String actor=payload.getUser().getId();
            if (REQUEST.equals(action)) open(payload.getTriggerId(), actor);
            else if (payload.getActions()!=null && !payload.getActions().isEmpty()) {
                String value=payload.getActions().getFirst().getValue();
                if (APPROVE.equals(action)) approve(actor,value);
                else if (EDIT.equals(action)) edit(payload.getTriggerId(),actor,value);
                else reject(payload.getTriggerId(),actor,value);
            }
            return ack;
        });
        app.viewSubmission(FORM, (request, context) -> {
            var ack=context.ack(); var payload=request.getPayload();
            if (payload.getUser()!=null && payload.getView()!=null) {
                var state=payload.getView().getState();
                submit(payload.getView().getId(), payload.getUser().getId(), field(state,"familyName",false),
                        field(state,"givenName",false),field(state,"team",true),field(state,"newTeam",false),field(state,"reason",false));
            }
            return ack;
        });
        app.viewClosed(FORM,(request,context) -> {
            var ack=context.ack(); var payload=request.getPayload();
            if (payload.getUser()!=null && payload.getView()!=null) close(payload.getView().getId(),payload.getUser().getId());
            return ack;
        });
    }

    public void open(String trigger, String actor) {
        try {
            if (SlackBotRenderer.mention(actor).isEmpty()) throw new IllegalArgumentException();
            if (registrations.pending(actor).isPresent()) { homes.publish(actor,"승인 대기 중이에요."); return; }
            openForm(trigger,actor,null,false);
        } catch (Exception failure) { failure(actor,failure); }
    }
    public void edit(String trigger, String actor, String value) {
        try { admins.requireAdmin(actor); openForm(trigger,actor,registrations.get(id(value),actor),false); }
        catch (Exception failure) { failure(actor,failure); }
    }
    public void reject(String trigger, String actor, String value) {
        try { admins.requireAdmin(actor); openForm(trigger,actor,registrations.get(id(value),actor),true); }
        catch (Exception failure) { failure(actor,failure); }
    }
    public void approve(String actor, String value) {
        try { admins.requireAdmin(actor); registrations.approve(id(value),actor); }
        catch (Exception failure) { failure(actor,failure); }
    }
    private void openForm(String trigger, String actor, RegistrationView registration, boolean reject) throws Exception {
        if (trigger==null || trigger.isBlank()) throw new IllegalArgumentException();
        if (registration!=null && registration.status()!=RegistrationStatus.PENDING) {
            notice(actor,"이미 처리된 신청이에요. 최신 알림을 확인해 주세요."); return;
        }
        List<String> teams=reject ? List.of() : new ArrayList<>(registrations.teams().stream().distinct().limit(100).toList());
        if (!reject && registration!=null && !teams.contains(registration.team())) {
            if (teams.size()==100) teams.removeLast();
            teams.add(registration.team());
        }
        synchronized(pending) {
            capacity();
            var response=client.viewsOpen(ViewsOpenRequest.builder().triggerId(trigger).viewAsString(
                    new GsonBuilder().disableHtmlEscaping().create().toJson(form(registration,reject,teams))).build());
            if (response==null || !response.isOk() || response.getView()==null || response.getView().getId()==null)
                throw new IllegalStateException();
            pending.put(response.getView().getId(),new Pending(actor,registration==null?null:registration.id(),reject,List.copyOf(teams),clock.instant().plusSeconds(1800)));
        }
    }
    public void submit(String viewId, String actor, String family, String given, String selectedTeam, String newTeam, String reason) {
        Pending origin;
        synchronized(pending) {
            expire(); origin=pending.get(viewId);
            if (origin!=null && origin.actor().equals(actor)) pending.remove(viewId);
        }
        if (origin==null || !origin.actor().equals(actor)) {
            notice(actor,"신청 화면이 만료됐거나 본인의 화면이 아니에요. Home 또는 최신 등록 알림에서 다시 열어 주세요."); return;
        }
        try {
            if (origin.requestId()!=null) admins.requireAdmin(actor);
            if (origin.reject()) {
                if (reason!=null && reason.length()>300) throw new IllegalArgumentException();
                registrations.reject(origin.requestId(),actor,reason==null || reason.isBlank()?null:reason.strip());
            } else {
                valid(family); valid(given);
                boolean selected=selectedTeam!=null && !selectedTeam.isBlank(), created=newTeam!=null && !newTeam.isBlank();
                if (selected==created || selected && !origin.teams().contains(selectedTeam)) throw new IllegalArgumentException();
                String team=selected?selectedTeam:newTeam; valid(team);
                if (origin.requestId()==null) registrations.submit(actor,family,given,team);
                else registrations.approve(origin.requestId(),actor,family,given,team);
            }
        } catch (Exception failure) { failure(actor,failure); }
    }
    public void close(String viewId,String actor) {
        synchronized(pending) {
            Pending origin=pending.get(viewId);
            if (origin!=null && origin.actor().equals(actor)) pending.remove(viewId);
        }
    }
    private Map<String,Object> form(RegistrationView registration, boolean reject, List<String> teams) {
        var blocks=new ArrayList<Map<String,Object>>();
        if (reject) blocks.add(input("reason","거절 사유 (선택)",null,true,300));
        else {
            blocks.add(input("familyName","성",registration==null?null:registration.familyName(),false,80));
            blocks.add(input("givenName","이름 (성 제외)",registration==null?null:registration.givenName(),false,80));
            if (!teams.isEmpty()) {
                var element=new HashMap<String,Object>(); element.put("type","static_select"); element.put("action_id","value");
                element.put("placeholder",plain("기존 팀 선택"));
                element.put("options",teams.stream().map(this::option).toList());
                if (registration!=null) element.put("initial_option",option(registration.team()));
                blocks.add(Map.of("type","input","block_id","team","optional",true,"label",plain("기존 팀"),"element",element));
            }
            blocks.add(input("newTeam","새 팀 이름 (기존 팀 미선택 시)",null,true,80));
            blocks.add(section("기존 팀 선택 또는 새 팀 이름 중 하나만 입력해 주세요. 기존 팀은 최대 100개까지 표시합니다."));
            if (registration!=null && registration.duplicateName()) blocks.add(section("동명이인 팀원이 있어요. 성·이름·팀을 확인한 뒤 승인해 주세요."));
        }
        return Map.of("type","modal","callback_id",FORM,"notify_on_close",true,"title",plain(reject?"등록 신청 거절":registration==null?"내 정보 등록":"정보 수정 후 승인"),
                "submit",plain(reject?"거절":registration==null?"신청":"승인"),"close",plain("취소"),"blocks",blocks);
    }
    private Map<String,Object> input(String id,String label,String initial,boolean optional,int max) {
        var element=new HashMap<String,Object>(); element.put("type","plain_text_input"); element.put("action_id","value"); element.put("max_length",max);
        if (!optional) element.put("min_length",1);
        if (initial!=null) element.put("initial_value",initial);
        return Map.of("type","input","block_id",id,"optional",optional,"label",plain(label),"element",element);
    }
    private Map<String,Object> option(String team) { return Map.of("text",plain(team.length()<=75?team:team.substring(0,74)+"…"),"value",team); }
    private Map<String,String> plain(String text) { return Map.of("type","plain_text","text",text); }
    private Map<String,Object> section(String text) { return Map.of("type","section","text",plain(text)); }
    private String field(ViewState state,String block,boolean select) {
        if (state==null || state.getValues()==null || state.getValues().get(block)==null) return null;
        var value=state.getValues().get(block).get("value");
        if (value==null || !(select?"static_select":"plain_text_input").equals(value.getType())) return null;
        return select?(value.getSelectedOption()==null?null:value.getSelectedOption().getValue()):value.getValue();
    }
    private long id(String value) { long id=Long.parseLong(value); if (id<=0) throw new IllegalArgumentException(); return id; }
    private void valid(String value) { if (value==null || value.isBlank() || value.length()>80) throw new IllegalArgumentException(); }
    private void capacity() { expire(); if (pending.size()>=1000) throw new IllegalStateException(); }
    private void expire() { pending.values().removeIf(value -> !clock.instant().isBefore(value.expiresAt())); }
    private void notice(String actor,String text) {
        homes.publish(actor,text);
        if (SlackBotRenderer.mention(actor).isEmpty()) return;
        try {
            var opened=client.conversationsOpen(ConversationsOpenRequest.builder().users(List.of(actor)).build());
            if (opened==null || !opened.isOk() || opened.getChannel()==null) throw new IllegalStateException();
            var sent=client.chatPostMessage(ChatPostMessageRequest.builder().channel(opened.getChannel().getId())
                    .text(SlackBotRenderer.escape(text)).parse("none").linkNames(false).unfurlLinks(false).unfurlMedia(false).build());
            if (sent==null || !sent.isOk()) throw new IllegalStateException();
        } catch (Exception ignored) { log.warn("Slack registration private notice failed: INTERNAL_ERROR"); }
    }
    private void failure(String actor,Exception failure) {
        log.warn("Slack member registration failed: {}",failure instanceof MemberManagementException member?member.getCode():"INTERNAL_ERROR");
        notice(actor,failure instanceof MemberManagementException?failure.getMessage():"신청을 처리하지 못했어요. 이름과 팀 입력을 확인하고 Home에서 다시 시도해 주세요.");
    }
}
