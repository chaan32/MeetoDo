package com.meetodo.member;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"MEETODO_ADMIN_SLACK_IDS=UADMIN,USECOND", "spring.datasource.url=jdbc:h2:mem:member-management-test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberManagementApiTests {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired com.meetodo.task.TaskRepository tasks;
    @Autowired MemberService service;

    @BeforeEach void clean() { tasks.deleteAll(); members.deleteAll(); }

    private String input(String id) {
        return "{\"familyName\":\"남궁\",\"givenName\":\"민수\",\"team\":\"백엔드팀\",\"slackUserId\":\"" + id + "\"}";
    }
    private Member existing(String id) { return members.saveAndFlush(new Member("이","지영","백엔드팀",id)); }


    @Test void administratorCanRegisterExplicitFamilyAndGivenNames() throws Exception {
        // 관리자가 명시한 성·이름·팀과 실제 Slack ID를 추측 없이 저장한다.
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json")
                .content("{\"familyName\":\"남궁\",\"givenName\":\"민수\",\"team\":\"백엔드팀\",\"slackUserId\":\"UMEMBER1\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("남궁민수"))
                .andExpect(jsonPath("$.active").value(true));
    }
    @Test void nonAdministratorCannotRegisterMember() throws Exception {
        // 관리자 목록에 없는 호출자는 팀원 등록을 수행할 수 없다.
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UOTHER").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        assertThat(members.count()).isZero();
    }
    @Test void missingActorCannotRegisterMember() throws Exception {
        // 관리자 ID를 생략한 요청은 기본 관리자처럼 취급하지 않는다.
        mvc.perform(post("/api/members").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isForbidden());
        assertThat(members.count()).isZero();
    }
    @Test void administratorCanUpdateMemberWithoutGuessingNameParts() throws Exception {
        // 팀 변경에도 성·이름 입력을 분리해 유지하고 연결 ID를 명시적으로 변경한다.
        Member member=existing("UMEMBER1");
        mvc.perform(patch("/api/members/{id}",member.getId()).header("X-Slack-User-Id","UADMIN").contentType("application/json")
                .content("{\"familyName\":\"제갈\",\"givenName\":\"유진\",\"team\":\"디자인팀\",\"slackUserId\":\"UUPDATED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("제갈유진"))
                .andExpect(jsonPath("$.familyName").value("제갈")).andExpect(jsonPath("$.givenName").value("유진"))
                .andExpect(jsonPath("$.team").value("디자인팀")).andExpect(jsonPath("$.slackUserId").value("UUPDATED"));
    }
    @Test void nonAdministratorCannotUpdateMember() throws Exception {
        // 수정 API에서도 등록과 같은 관리자 권한을 검사한다.
        Member member=existing("UMEMBER1");
        mvc.perform(patch("/api/members/{id}",member.getId()).header("X-Slack-User-Id","UOTHER").contentType("application/json").content(input("UUPDATED")))
                .andExpect(status().isForbidden());
        assertThat(members.findById(member.getId()).orElseThrow().getSlackUserId()).isEqualTo("UMEMBER1");
    }
    @Test void administratorDeactivatesWithoutDeletingMemberOrHistoricalTask() throws Exception {
        // 비활성화는 원래 팀원과 기존 할 일의 담당자 연결을 보존한다.
        Member member=existing("UMEMBER1");
        var task=tasks.saveAndFlush(com.meetodo.task.Task.create("기존 업무",member,null));
        mvc.perform(post("/api/members/{id}/deactivate",member.getId()).header("X-Slack-User-Id","UADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/members")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(member.getId())).andExpect(jsonPath("$[0].active").value(false));
        assertThat(members.count()).isEqualTo(1);
        assertThat(tasks.findById(task.getId()).orElseThrow().getAssignee().getId()).isEqualTo(member.getId());
    }
    @Test void nonAdministratorCannotDeactivateMember() throws Exception {
        // 퇴사·이동 처리는 다른 일반 사용자가 수행할 수 없다.
        Member member=existing("UMEMBER1");
        mvc.perform(post("/api/members/{id}/deactivate",member.getId()).header("X-Slack-User-Id","UOTHER"))
                .andExpect(status().isForbidden());
    }
    @Test void duplicateSlackUserCannotBeRegisteredTwice() throws Exception {
        // 같은 Slack 사용자를 두 팀원에 연결하면409로 거절한다.
        existing("UMEMBER1");
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_SLACK_USER_CONFLICT"));
        assertThat(members.count()).isEqualTo(1);
    }
    @Test void duplicateSlackUserCannotBeAssignedDuringUpdate() throws Exception {
        // 수정 경로에서도 다른 팀원에 연결된 Slack ID를 빼앗지 못한다.
        Member first=existing("UMEMBER1"); Member second=existing("UMEMBER2");
        mvc.perform(patch("/api/members/{id}",second.getId()).header("X-Slack-User-Id","UADMIN").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isConflict());
        assertThat(members.findById(first.getId()).orElseThrow().getSlackUserId()).isEqualTo("UMEMBER1");
        assertThat(members.findById(second.getId()).orElseThrow().getSlackUserId()).isEqualTo("UMEMBER2");
    }
    @Test void deactivatedMemberStillReservesItsSlackUserIdentity() throws Exception {
        // 비활성화는 삭제가 아니므로 동일 Slack ID의 새 팀원 등록도 막는다.
        Member member=existing("UMEMBER1");
        mvc.perform(post("/api/members/{id}/deactivate",member.getId()).header("X-Slack-User-Id","UADMIN"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isConflict());
    }
    @Test void blankMemberNameIsRejectedAsInputError() throws Exception {
        // 새 등록 API도 빈 이름을 허용하지 않는다.
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json")
                .content(input("UMEMBER1").replace("남궁"," ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.familyName").exists());
    }
    @Test void fakeDevelopmentSlackIdCannotBeRegisteredAsRealUser() throws Exception {
        // 사용자 선택 대신 개발용 가짜 식별자를 관리 API에 보내면 거절한다.
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json").content(input("U-DEV-FAKE")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.slackUserId").exists());
    }
    @Test void unknownMemberRequestFieldIsRejected() throws Exception {
        // 관리 API도 HTTP 요청 DTO 밖의 필드를 조용히 무시하지 않는다.
        mvc.perform(post("/api/members").header("X-Slack-User-Id","UADMIN").contentType("application/json")
                .content(input("UMEMBER1").replace("}",",\"active\":true}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.active").exists());
    }
    @Test void missingMemberUpdateReturnsNotFound() throws Exception {
        // 없는 팀원 수정은 새 행을 만들지 않고404로 알려준다.
        mvc.perform(patch("/api/members/99999").header("X-Slack-User-Id","UADMIN").contentType("application/json").content(input("UMEMBER1")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test void reusableServiceValidatesInputWithoutHttpController() {
        // 이후 Slack 모달도 같은 서비스 경계에서 DTO 검증을 받는다.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(
                new com.meetodo.member.dto.MemberRequest(" ","민수","백엔드팀","UMEMBER1"),"UADMIN"))
                .isInstanceOf(jakarta.validation.ConstraintViolationException.class);
        assertThat(members.count()).isZero();
    }
    @Test void missingMemberDeactivationReturnsNotFound() throws Exception {
        // 없는 팀원의 퇴사 처리도404로 구분한다.
        mvc.perform(post("/api/members/99999/deactivate").header("X-Slack-User-Id","UADMIN"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

}
