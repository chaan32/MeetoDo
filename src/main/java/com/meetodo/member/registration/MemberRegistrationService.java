package com.meetodo.member.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.member.*;
import com.meetodo.member.dto.MemberRequest;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MemberRegistrationService {
    private final MemberRegistrationRepository requests;
    private final MemberRegistrationAuditRepository audits;
    private final MemberRepository members;
    private final MemberService memberService;
    private final AdminAuthorization authorization;
    private final Validator validator;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public MemberRegistrationService(MemberRegistrationRepository requests, MemberRegistrationAuditRepository audits,
            MemberRepository members, MemberService memberService, AdminAuthorization authorization,
            Validator validator, ObjectMapper mapper, ApplicationEventPublisher events, Clock clock) {
        this.requests=requests; this.audits=audits; this.members=members; this.memberService=memberService;
        this.authorization=authorization; this.validator=validator; this.mapper=mapper; this.events=events; this.clock=clock;
    }

    public RegistrationView submit(String user, String family, String given, String team) {
        MemberRequest form=validated(user,family,given,team);
        requireUnregistered(user);
        var pending=requests.findByPendingSlackUserId(user);
        if (pending.isPresent()) return view(pending.get());
        var request=new MemberRegistrationRequest(user,form.familyName(),form.givenName(),form.team(),
                duplicateName(form),clock.instant());
        try { requests.saveAndFlush(request); }
        catch (DataIntegrityViolationException ignored) {
            // Throw out of the failed transaction; never try to read or recover inside it.
            throw error(HttpStatus.CONFLICT,"REGISTRATION_PENDING_CONFLICT","이미 처리 중인 등록 신청이 있어요. 다시 확인해 주세요.");
        }
        requireUnregistered(user);
        audit(request,"SUBMITTED",user,null,json(form));
        events.publishEvent(new MemberRegistrationChangedEvent(request.getId()));
        return view(request);
    }

    @Transactional(readOnly=true)
    public Optional<RegistrationView> pending(String user) {
        validateUser(user);
        return requests.findByPendingSlackUserId(user).map(this::view);
    }

    @Transactional(readOnly=true)
    public RegistrationView get(Long id, String actor) {
        authorization.requireAdmin(actor);
        return getForNotification(id);
    }

    @Transactional(readOnly=true)
    public RegistrationView getForNotification(Long id) {
        return view(requests.findById(id).orElseThrow(this::notFound));
    }

    public RegistrationView approve(Long id, String actor) {
        authorization.requireAdmin(actor);
        var request=locked(id);
        if (request.getStatus()!=RegistrationStatus.PENDING) return view(request);
        return approve(request,actor,validated(request.getSlackUserId(),request.getFamilyName(),request.getGivenName(),request.getTeam()));
    }

    public RegistrationView approve(Long id, String actor, String family, String given, String team) {
        authorization.requireAdmin(actor);
        var request=locked(id);
        if (request.getStatus()!=RegistrationStatus.PENDING) return view(request);
        return approve(request,actor,validated(request.getSlackUserId(),family,given,team));
    }

    private RegistrationView approve(MemberRegistrationRequest request,String actor,MemberRequest form) {
        String before=json(new MemberRequest(request.getFamilyName(),request.getGivenName(),request.getTeam(),request.getSlackUserId()));
        String after=json(form);
        boolean duplicate=duplicateName(form);
        var member=memberService.create(form,actor);
        request.approve(actor,form.familyName(),form.givenName(),form.team(),member.id(),duplicate,clock.instant());
        audit(request,"APPROVED",actor,before.equals(after)?null:before,before.equals(after)?null:after);
        events.publishEvent(new MemberRegistrationChangedEvent(request.getId()));
        return view(request);
    }

    public RegistrationView reject(Long id, String actor, String reason) {
        authorization.requireAdmin(actor);
        var request=locked(id);
        if (request.getStatus()!=RegistrationStatus.PENDING) return view(request);
        if (reason!=null && reason.length()>300) throw validationError();
        request.reject(actor,reason==null?null:reason.strip(),clock.instant());
        audit(request,"REJECTED",actor,null,null);
        events.publishEvent(new MemberRegistrationChangedEvent(request.getId()));
        return view(request);
    }

    @Transactional(readOnly=true)
    public List<String> teams() {
        return members.findAll().stream().filter(Member::isActive).map(Member::getTeam).distinct().sorted().toList();
    }

    private MemberRegistrationRequest locked(Long id) {
        return requests.findLockedById(id).orElseThrow(this::notFound);
    }
    private MemberRequest validated(String user,String family,String given,String team) {
        var request=new MemberRequest(family,given,team,user);
        if (!validator.validate(request).isEmpty()) throw validationError();
        return new MemberRequest(family.strip(),given.strip(),team.strip(),user);
    }
    private void validateUser(String user) {
        if (user==null || !user.matches("[UW][A-Z0-9]{1,79}")) throw validationError();
    }
    private void requireUnregistered(String user) {
        if (members.existsBySlackUserId(user)) throw error(HttpStatus.CONFLICT,"MEMBER_ALREADY_REGISTERED","이미 등록된 Slack 사용자예요. 관리자에게 문의해 주세요.");
    }
    private boolean duplicateName(MemberRequest form) {
        return members.findAll().stream().filter(Member::isActive).anyMatch(member ->
                member.getFullName().equals(form.familyName()+form.givenName()) || member.getGivenName().equals(form.givenName()));
    }
    private RegistrationView view(MemberRegistrationRequest r) {
        boolean duplicate=r.getStatus()==RegistrationStatus.PENDING
                ? duplicateName(new MemberRequest(r.getFamilyName(),r.getGivenName(),r.getTeam(),r.getSlackUserId()))
                : r.isDuplicateName();
        return new RegistrationView(r.getId(),r.getSlackUserId(),r.getFamilyName(),r.getGivenName(),r.getTeam(),
                r.getStatus(),r.getProcessedBySlackUserId(),r.getRejectionReason(),duplicate,r.getMemberId());
    }
    private void audit(MemberRegistrationRequest request,String type,String actor,String before,String after) {
        audits.save(new MemberRegistrationAudit(request,type,actor,before,after,clock.instant()));
    }
    private String json(MemberRequest form) {
        try { return mapper.writeValueAsString(Map.of("familyName",form.familyName(),"givenName",form.givenName(),"team",form.team())); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Registration form serialization failed."); }
    }
    private MemberManagementException validationError() {
        return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","성·이름·팀과 Slack 사용자 ID 또는 반려 사유를 확인해 주세요.");
    }
    private MemberManagementException notFound() {
        return error(HttpStatus.NOT_FOUND,"REGISTRATION_NOT_FOUND","등록 신청을 찾지 못했어요.");
    }
    private MemberManagementException error(HttpStatus status,String code,String message) {
        return new MemberManagementException(status,code,message);
    }
}
