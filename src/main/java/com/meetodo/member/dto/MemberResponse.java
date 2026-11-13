package com.meetodo.member.dto;

import com.meetodo.member.Member;

public record MemberResponse(Long id, String familyName, String givenName, String name,
                             String team, String slackUserId, boolean active) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId(), member.getFamilyName(), member.getGivenName(), member.getFullName(),
                member.getTeam(), member.getSlackUserId(), member.isActive());
    }
}
