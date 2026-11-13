package com.meetodo.member;

import com.meetodo.member.dto.MemberRequest;
import com.meetodo.member.dto.MemberResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@Transactional
public class MemberService {
    private final MemberRepository members;
    private final AdminAuthorization authorization;
    public MemberService(MemberRepository members, AdminAuthorization authorization) {
        this.members = members; this.authorization = authorization;
    }
    private org.springframework.context.ApplicationEventPublisher events = event -> {};
    @org.springframework.beans.factory.annotation.Autowired
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher events) { this.events = events; }
    @Transactional(readOnly = true)
    public List<MemberResponse> findAll() {
        return members.findAll(Sort.by("id")).stream().map(MemberResponse::from).toList();
    }
    public MemberResponse create(@Valid @NotNull MemberRequest request, String actor) {
        authorization.requireAdmin(actor);
        if (members.existsBySlackUserId(request.slackUserId())) throw conflict();
        return save(new Member(request.familyName().strip(), request.givenName().strip(), request.team().strip(), request.slackUserId()));
    }
    public MemberResponse update(Long id, @Valid @NotNull MemberRequest request, String actor) {
        authorization.requireAdmin(actor);
        Member member = find(id);
        if (members.existsBySlackUserIdAndIdNot(request.slackUserId(), id)) throw conflict();
        member.update(request.familyName().strip(), request.givenName().strip(), request.team().strip(), request.slackUserId());
        return save(member);
    }
    public MemberResponse deactivate(Long id, String actor) {
        authorization.requireAdmin(actor);
        Member member = find(id); member.deactivate();
        events.publishEvent(new MemberChangedEvent());
        return MemberResponse.from(member);
    }
    private Member find(Long id) {
        return members.findById(id).orElseThrow(() -> new MemberManagementException(HttpStatus.NOT_FOUND,
                "MEMBER_NOT_FOUND", "팀원을 찾지 못했어요."));
    }
    private MemberResponse save(Member member) {
        try {
            MemberResponse response = MemberResponse.from(members.saveAndFlush(member));
            events.publishEvent(new MemberChangedEvent());
            return response;
        }
        catch (DataIntegrityViolationException ignored) { throw conflict(); }
    }
    private MemberManagementException conflict() {
        return new MemberManagementException(HttpStatus.CONFLICT, "MEMBER_SLACK_USER_CONFLICT", "이미 다른 팀원에 연결된 Slack 사용자예요.");
    }
}
