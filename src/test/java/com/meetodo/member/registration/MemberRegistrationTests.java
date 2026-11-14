package com.meetodo.member.registration;

import com.meetodo.member.Member;
import com.meetodo.member.MemberRepository;
import com.meetodo.member.MemberManagementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="MEETODO_ADMIN_SLACK_IDS=UADMIN,UOTHER")
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.event.RecordApplicationEvents
class MemberRegistrationTests {
    @Autowired MemberRegistrationService service;
    @Autowired MemberRepository members;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired MemberRegistrationAuditRepository audits;
    @Autowired org.springframework.test.context.event.ApplicationEvents events;

    @Test void pendingApplicationCreatesNoMember() {
        // 신청은 Member를 만들지 않고 본인에게 대기 신청을 반환한다.
        var view=service.submit("UAPPLY","김","민수","개발팀");
        assertThat(view.status()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(members.existsBySlackUserId("UAPPLY")).isFalse();
        assertThat(service.pending("UAPPLY")).contains(view);
    }
    @Test void approvalCreatesMemberAndRepeatDecisionKeepsFirstActor() {
        // 승인 이후 반려를 눌러도 최초 승인과 처리자만 남는다.
        var request=service.submit("UAPPROVE","김","민수","개발팀");
        var approved=service.approve(request.id(),"UADMIN");
        assertThat(approved.status()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(approved.memberId()).isNotNull();
        assertThat(service.reject(request.id(),"UOTHER","늦은 반려")).isEqualTo(approved);
    }
    @Test void rejectedUserCanSubmitAgain() {
        // 반려 완료 뒤에는 같은 Slack 사용자도 새 신청을 만들 수 있다.
        var request=service.submit("URETRY","김","민수","개발팀");
        service.reject(request.id(),"UADMIN",null);
        assertThat(service.submit("URETRY","이","지영","디자인팀").id()).isNotEqualTo(request.id());
    }
    @Test void nonAdminCannotReadOrDecideEvenUnknownRequest() {
        // 관리자 검증을 신청 존재 확인보다 먼저 한다.
        assertThatThrownBy(() -> service.get(999L,"USTRANGER")).isInstanceOfSatisfying(MemberManagementException.class,
                error -> assertThat(error.getCode()).isEqualTo("ADMIN_REQUIRED"));
    }
    @Test void inactiveIdentityCannotApplyAgain() {
        // 비활성 Member도 같은 Slack ID의 새 신청을 막는다.
        var member=new Member("김","민수","개발팀","UINACTIVE"); member.deactivate(); members.saveAndFlush(member);
        assertThatThrownBy(() -> service.submit("UINACTIVE","김","민수","개발팀")).isInstanceOf(MemberManagementException.class);
    }
    @Test void invalidSlackIdentityAndOversizedFormAreRejected() {
        // 서비스 직접 호출도 Bean Validation 제약을 통과해야 한다.
        assertThatThrownBy(() -> service.submit("invalid","김","민수","개발팀")).isInstanceOf(MemberManagementException.class);
        assertThatThrownBy(() -> service.submit("ULONG","김".repeat(81),"민수","개발팀")).isInstanceOf(MemberManagementException.class);
    }
    @Test void duplicatePendingSubmissionReusesOriginalWithoutAuditOrEdit() {
        // 대기 중 재전송은 원본 신청과 최초 폼을 보존한다.
        var original=service.submit("UPENDING","김","민수","개발팀"); long count=audits.count();
        assertThat(service.submit("UPENDING","이","지영","다른팀")).isEqualTo(original);
        assertThat(audits.count()).isEqualTo(count);
    }
    @Test void correctedApprovalAuditsOriginalAndCorrectedForm() {
        // 수정 승인에는 수정 전후 폼과 실제 처리자가 남는다.
        var original=service.submit("UCORRECT","김","민수","개발팀");
        var approved=service.approve(original.id(),"UADMIN","이","지영","디자인팀"); audits.flush();
        assertThat(approved.familyName()).isEqualTo("이");
        assertThat(jdbc.queryForMap("select before_form,after_form,by_slack_user_id from member_registration_events where request_id=? and type='APPROVED'", original.id()))
                .satisfies(row -> {
                    assertThat(row.get("before_form").toString()).contains("민수","개발팀");
                    assertThat(row.get("after_form").toString()).contains("지영","디자인팀");
                    assertThat(row.get("by_slack_user_id")).isEqualTo("UADMIN");
                });
    }
    @Test void duplicateGivenNameWarnsButDoesNotBlockApproval() {
        // 활성 팀원과 이름이 같아도 경고만 표시하고 승인할 수 있다.
        members.saveAndFlush(new Member("박","민수","다른팀","UEXISTING"));
        var view=service.submit("USAMENAME","김","민수","개발팀");
        assertThat(view.duplicateName()).isTrue();
        assertThat(service.approve(view.id(),"UADMIN").status()).isEqualTo(RegistrationStatus.APPROVED);
    }
    @Test void pendingViewWarnsWhenMatchingActiveMemberWasAddedAfterSubmission() {
        // 대기 중 추가된 동명이인도 관리자 확인 화면과 알림 조회에 반영한다.
        var view=service.submit("ULATENAME","김","민수","개발팀");
        assertThat(view.duplicateName()).isFalse();
        members.saveAndFlush(new Member("박","민수","다른팀","ULATEEXISTING"));
        assertThat(service.get(view.id(),"UADMIN").duplicateName()).isTrue();
        assertThat(service.getForNotification(view.id()).duplicateName()).isTrue();
    }
    @Test void terminalViewKeepsDuplicateNameSnapshotAfterOtherMemberChanges() {
        // 처리 완료 응답은 이후 팀원 추가로 이름 경고가 달라지지 않는다.
        var view=service.submit("UFROZENNAME","김","민수","개발팀");
        var approved=service.approve(view.id(),"UADMIN");
        assertThat(approved.duplicateName()).isFalse();
        members.saveAndFlush(new Member("박","민수","다른팀","UFROZENEXISTING"));
        assertThat(service.get(view.id(),"UADMIN")).isEqualTo(approved);
        assertThat(service.getForNotification(view.id())).isEqualTo(approved);
        assertThat(service.reject(view.id(),"UOTHER",null)).isEqualTo(approved);
    }
    @Test void teamsAreActiveDistinctAndSorted() {
        // 신청 폼 선택지는 활성 팀원의 팀만 중복 없이 정렬한다.
        members.deleteAll();
        members.save(new Member("김","민수","B","UTEAMB")); members.save(new Member("이","지영","A","UTEAMA"));
        var inactive=new Member("박","철수","C","UTEAMC"); inactive.deactivate(); members.save(inactive);
        members.save(new Member("최","영수","A","UTEAMD"));
        assertThat(service.teams()).containsExactly("A","B");
    }
    @Test void oversizedRejectionReasonLeavesRequestPending() {
        // 반려 사유의 길이 초과는 처리 상태를 바꾸기 전에 거절한다.
        var view=service.submit("UREASON","김","민수","개발팀");
        assertThatThrownBy(() -> service.reject(view.id(),"UADMIN","x".repeat(301))).isInstanceOf(MemberManagementException.class);
        assertThat(service.pending("UREASON")).isPresent();
    }
    @Test void eventsArePublishedOnlyForNewSubmissionAndFirstDecision() {
        // 대기 재전송과 완료 후 재클릭은 알림 이벤트를 추가로 만들지 않는다.
        var view=service.submit("UEVENTS","김","민수","개발팀");
        service.submit("UEVENTS","김","민수","개발팀");
        service.approve(view.id(),"UADMIN");
        service.approve(view.id(),"UOTHER");
        service.reject(view.id(),"UOTHER",null);
        assertThat(events.stream(MemberRegistrationChangedEvent.class).count()).isEqualTo(2);
    }
    @Test void invalidCorrectedFormLeavesApplicationPending() {
        // 수정 승인도 최초 신청과 같은 폼 검증을 적용한다.
        var view=service.submit("UINVALIDEDIT","김","민수","개발팀");
        assertThatThrownBy(() -> service.approve(view.id(),"UADMIN","", "민수","개발팀"))
                .isInstanceOf(MemberManagementException.class);
        assertThat(members.existsBySlackUserId("UINVALIDEDIT")).isFalse();
        assertThat(service.pending("UINVALIDEDIT")).isPresent();
    }
}
