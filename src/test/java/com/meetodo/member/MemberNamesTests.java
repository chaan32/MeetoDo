package com.meetodo.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.meeting.Meeting;
import com.meetodo.meeting.MeetingSource;
import com.meetodo.meeting.ToolCallLogRepository;
import com.meetodo.tools.FindMemberTool;
import com.meetodo.tools.ToolExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DataJpaTest
@ActiveProfiles("test")
class MemberNamesTests {
    @Autowired MemberRepository members;

    private Member member(String family, String given, String team) {
        return members.saveAndFlush(new Member(family, given, team, null));
    }

    private FindMemberTool tool() {
        return new FindMemberTool(members,
                new ToolExecutionService(mock(ToolCallLogRepository.class), new ObjectMapper()));
    }

    @Test
    void honorificLookupFindsRegisteredGivenName() {
        // 모델이 전달한 호칭 이름도 등록된 이름으로 조회해야 한다.
        member("이", "지영", "백엔드팀");
        assertThat(tool().findMember("지영님", Meeting.create("호칭 조회 회의", MeetingSource.API_TEXT)).success()).isTrue();
    }

    @Test
    void persistsExplicitCompoundFamilyWithoutSplitting() {
        // 복성은 추정하지 않고 입력한 성·이름 그대로 보존한다.
        Member saved = member("남궁", "민", "백엔드팀");
        assertThat(saved.getFamilyName()).isEqualTo("남궁");
        assertThat(saved.getGivenName()).isEqualTo("민");
        assertThat(saved.getFullName()).isEqualTo("남궁민");
        assertThat(saved.getName()).isEqualTo("남궁민");
        assertThat(MemberNames.find(members, "남궁민")).containsExactly(saved);
    }

    @Test
    void exactFullNameWinsOverAnotherMembersGivenName() {
        // 동일 문자열이 성명과 다른 사람의 이름이면 성명이 우선이다.
        Member exact = member("이", "지영", "백엔드팀");
        member("김", "이지영", "디자인팀");
        assertThat(MemberNames.find(members, "이지영")).containsExactly(exact);
    }

    @Test
    void givenNameReturnsEveryCandidateInIdOrder() {
        // 성이 다른 같은 이름도 모두 후보이며 임의로 첫 사람을 고르지 않는다.
        Member first = member("이", "지영", "백엔드팀");
        Member second = member("김", "지영", "디자인팀");
        assertThat(MemberNames.find(members, " 지영 ")).containsExactly(first, second);
    }

    @Test
    void removesOneHonorificWithOptionalWhitespace() {
        // 성명 뒤 공백과 씨 한 개를 제거한 다음 정확히 재조회한다.
        Member saved = member("김", "민수", "백엔드팀");
        assertThat(MemberNames.find(members, " 김민수 씨 ")).containsExactly(saved);
    }

    @Test
    void rawNameEndingWithSsiWinsBeforeHonorificRemoval() {
        // 실제 이름의 마지막 씨를 호칭으로 먼저 오해하지 않는다.
        Member exact = member("", "씨", "백엔드팀");
        assertThat(MemberNames.find(members, "씨")).containsExactly(exact);
    }

    @Test
    void doesNotStripMoreThanOneHonorific() {
        // 반복 호칭을 여러 번 지우며 이름을 추측하지 않는다.
        member("김", "민수", "백엔드팀");
        assertThat(MemberNames.find(members, "민수님님")).isEmpty();
    }

    @Test
    void doesNotMatchPartialNickname() {
        // 이름의 일부나 별칭으로 담당자를 추측하지 않는다.
        member("김", "민수", "백엔드팀");
        assertThat(MemberNames.find(members, "민")).isEmpty();
    }

    @Test
    void doesNotStripRole() {
        // 서버가 직급을 자의적으로 제거하지 않는다.
        member("이", "지영", "백엔드팀");
        assertThat(MemberNames.find(members, "지영 대리")).isEmpty();
    }

    @Test
    void doesNotStripParticle() {
        // 서버가 조사를 제거해 다른 이름으로 조회하지 않는다.
        member("김", "민수", "백엔드팀");
        assertThat(MemberNames.find(members, "민수님이")).isEmpty();
    }

    @Test
    void honorificOnlyInputIsInvalidWhenNoExactNameExists() {
        // 호칭만 전달된 입력은 빈 이름과 같이 거절한다.
        var result = tool().findMember("님", Meeting.create("빈 이름 회의", MeetingSource.API_TEXT));
        assertThat(result.errorCode()).isEqualTo("INVALID_MEMBER_NAME");
    }

    @Test
    void blankInputIsInvalid() {
        // 공백 이름은 저장소 후보가 있어도 조회하지 않는다.
        var result = tool().findMember("  ", Meeting.create("공백 이름 회의", MeetingSource.API_TEXT));
        assertThat(result.errorCode()).isEqualTo("INVALID_MEMBER_NAME");
    }

    @Test
    void teamFilterKeepsSameFullNameAmbiguityWithinTeam() {
        // 성명이 같고 팀도 같으면 여전히 복수 후보다.
        member("이", "지영", "백엔드팀");
        member("이", "지영", "백엔드팀");
        member("이", "지영", "디자인팀");
        var result = tool().findMember("이지영님", "백엔드팀", Meeting.create("백엔드팀 업무 회의", MeetingSource.API_TEXT));
        assertThat(result.success()).isTrue();
        assertThat(result.message()).startsWith("AMBIGUOUS:");
        assertThat(result.data()).doesNotContain("디자인팀");
    }

    @Test
    void teamMismatchNeverFallsBackToDifferentIdentity() {
        // 성명 우선 조회에서 팀이 달라도 같은 문자열의 이름 후보로 바꾸지 않는다.
        member("이", "지영", "디자인팀");
        member("김", "이지영", "백엔드팀");
        var result = tool().findMember("이지영", "백엔드팀", Meeting.create("백엔드팀 업무 회의", MeetingSource.API_TEXT));
        assertThat(result.errorCode()).isEqualTo("MEMBER_TEAM_NOT_FOUND");
    }

    @Test
    void teamGuardMatchesFullGivenAndHonorificAliases() {
        // 팀 불일치 보호가 성명·이름·호칭 표현을 같은 후보로 인식한다.
        Member saved = member("이", "지영", "백엔드팀");
        assertThat(MemberNames.matches(saved, "이지영")).isTrue();
        assertThat(MemberNames.matches(saved, "지영")).isTrue();
        assertThat(MemberNames.matches(saved, "지영 님")).isTrue();
        assertThat(MemberNames.matches(saved, "지영 대리는")).isFalse();
    }
    @Test void inactiveMembersAreExcludedFromFullAndGivenNameLookup() {
        // 비활성 팀원은 성명·이름 후보에서 제외하고 DB의 과거 행은 유지한다.
        Member inactive=member("김","민수","백엔드팀"); inactive.deactivate(); members.saveAndFlush(inactive);
        assertThat(MemberNames.find(members,"김민수")).isEmpty();
        assertThat(MemberNames.find(members,"민수")).isEmpty();
        assertThat(members.findById(inactive.getId())).isPresent();
    }
    @Test void inactiveFullNameDoesNotHideActiveGivenNameCandidate() {
        // 비활성 성명 일치가 있어도 활성 이름 후보를 숨기지 않는다.
        Member inactive=member("이","지영","백엔드팀"); inactive.deactivate(); members.saveAndFlush(inactive);
        Member active=member("김","이지영","디자인팀");
        assertThat(MemberNames.find(members,"이지영")).containsExactly(active);
    }

}
