package com.meetodo.member;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local & !test")
public class DevMemberDataInitializer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DevMemberDataInitializer.class);

    @Bean
    ApplicationRunner seedDevelopmentMembers(MemberRepository members,
            @org.springframework.beans.factory.annotation.Value("${DEV_SLACK_USER_ID:}") String slackUserId) {
        return args -> {
            addIfMissing(members, "김", "민수", "백엔드팀", "U-DEV-MINSU");
            addIfMissing(members, "이", "지영", "백엔드팀", "U-DEV-JIYOUNG-BACKEND");
            addIfMissing(members, "이", "지영", "디자인팀", "U-DEV-JIYOUNG-DESIGN");
            String actualId = slackUserId != null && slackUserId.matches("[UW][A-Z0-9]{1,79}") ? slackUserId : null;
            var existing = members.findAllByNameOrderByIdAsc("전해찬").stream()
                    .filter(member -> "백엔드팀".equals(member.getTeam())).toList();
            if (existing.size() > 1) {
                log.warn("Development Slack ID seed skipped because sample identity is ambiguous");
            } else if (existing.size() == 1) {
                Member member = existing.getFirst();
                if (actualId != null && !actualId.equals(member.getSlackUserId())) {
                    member.changeSlackUserId(actualId);
                    members.save(member);
                }
            } else {
                addIfMissing(members, "전", "해찬", "백엔드팀", actualId == null ? "U-DEV-HAECHAN" : actualId);
            }
        };
    }

    private void addIfMissing(MemberRepository members, String familyName, String givenName, String team, String slackUserId) {
        if (!members.existsBySlackUserId(slackUserId)) {
            members.save(new Member(familyName, givenName, team, slackUserId));
        }
    }
}
