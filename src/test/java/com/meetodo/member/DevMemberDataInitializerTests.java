package com.meetodo.member;

import com.meetodo.tools.FindMemberTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DevMemberDataInitializerTests {
    // 새 개발 샘플은 성을 명시하며 전체 이름과 팀 조합을 그대로 제공한다.
    @Test void seedsExplicitFamilyGivenAndTeamForFourDevelopmentMembers() throws Exception {
        var members = mock(MemberRepository.class);
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "").run(null);
        var captor = org.mockito.ArgumentCaptor.forClass(Member.class);
        verify(members, times(4)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(m -> m.getFullName() + "/" + m.getTeam())
                .containsExactly("김민수/백엔드팀", "이지영/백엔드팀", "이지영/디자인팀", "전해찬/백엔드팀");
        assertThat(captor.getAllValues()).extracting(Member::getFamilyName).containsExactly("김", "이", "이", "전");
    }

    // 같은 Slack ID가 이미 있으면 시작할 때 샘플을 다시 추가하거나 이름을 추측해 갱신하지 않는다.
    @Test void doesNotBackfillOrDuplicateExistingDevelopmentMembers() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "").run(null);
        verify(members, never()).save(any());
    }

    // 기존 개발 계정은 실제 Slack ID로 갱신하며 새 행을 추가하지 않는다.
    @Test void updatesKnownDevelopmentMemberWithoutDuplicating() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        var member = new Member("전", "해찬", "백엔드팀", "U-DEV-HAECHAN");
        when(members.findAllByNameOrderByIdAsc("전해찬")).thenReturn(java.util.List.of(member));
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "U123ABC").run(null);
        assertThat(member.getSlackUserId()).isEqualTo("U123ABC");
        verify(members).save(member);
    }

    // 같은 전체 이름과 팀이 여러 명이면 임의의 사람에게 Slack ID를 배정하지 않는다.
    @Test void doesNotUpdateAmbiguousDevelopmentMembers() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        var first = new Member("전", "해찬", "백엔드팀", "U-DEV-HAECHAN");
        var second = new Member("전", "해찬", "백엔드팀", "U999ABC");
        when(members.findAllByNameOrderByIdAsc("전해찬")).thenReturn(java.util.List.of(first, second));
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "U123ABC").run(null);
        assertThat(first.getSlackUserId()).isEqualTo("U-DEV-HAECHAN");
        assertThat(second.getSlackUserId()).isEqualTo("U999ABC");
        verify(members, never()).save(any());
    }

    // 잘못된 환경 변수는 기존 ID를 덮어쓰지 않는다.
    @Test void ignoresMalformedDevelopmentSlackId() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        var member = new Member("전", "해찬", "백엔드팀", "U123ABC");
        when(members.findAllByNameOrderByIdAsc("전해찬")).thenReturn(java.util.List.of(member));
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "U-DEV-HAECHAN").run(null);
        assertThat(member.getSlackUserId()).isEqualTo("U123ABC");
        verify(members, never()).save(any());
    }

    // 환경 변수가 없어도 기존 실제 Slack ID를 가짜 개발 ID로 되돌리지 않는다.
    @Test void preservesActualSlackIdWhenEnvironmentIsAbsent() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        var member = new Member("전", "해찬", "백엔드팀", "U123ABC");
        when(members.findAllByNameOrderByIdAsc("전해찬")).thenReturn(java.util.List.of(member));
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "").run(null);
        assertThat(member.getSlackUserId()).isEqualTo("U123ABC");
        verify(members, never()).save(any());
    }

    // 새 개발 계정에도 제공된 실제 ID를 사용한다.
    @Test void seedsActualSlackIdForNewDevelopmentMember() throws Exception {
        var members = mock(MemberRepository.class);
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "W123ABC").run(null);
        var captor = org.mockito.ArgumentCaptor.forClass(Member.class);
        verify(members, times(4)).save(captor.capture());
        assertThat(captor.getAllValues().getLast().getSlackUserId()).isEqualTo("W123ABC");
    }

    // 다른 팀원이 이미 사용하는 실제 Slack ID로 샘플을 덮어쓰지 않는다.
    @Test void skipsDevelopmentSlackIdAlreadyLinkedToAnotherMember() throws Exception {
        var members = mock(MemberRepository.class);
        when(members.existsBySlackUserId(any())).thenReturn(true);
        var member = new Member("전", "해찬", "백엔드팀", "U-DEV-HAECHAN");
        when(members.findAllByNameOrderByIdAsc("전해찬")).thenReturn(java.util.List.of(member));
        when(members.existsBySlackUserIdAndIdNot("U123ABC", member.getId())).thenReturn(true);
        new DevMemberDataInitializer().seedDevelopmentMembers(members, "U123ABC").run(null);
        assertThat(member.getSlackUserId()).isEqualTo("U-DEV-HAECHAN");
        verify(members, never()).save(any());
    }

    // 모델에게 보이는 도구 설명과 이름 파라미터 모두 직급·조사 추출 책임을 명시한다.
    @Test void toolAndNameParameterExplainModelExtractionResponsibility() throws Exception {
        var method = FindMemberTool.class.getMethod("invokeFindMember", String.class, String.class, ToolContext.class);
        String guidance = "name에는 호칭·직급·조사를 뺀 이름만 넣으세요. 예: '지영 대리는' → '지영', '민수 팀장님이' → '민수'";
        assertThat(method.getAnnotation(Tool.class).description()).contains(guidance);
        assertThat(method.getParameters()[0].getAnnotation(ToolParam.class).description()).contains(guidance);
    }
}
