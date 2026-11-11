package com.meetodo.member;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local & !test")
public class DevMemberDataInitializer {

    @Bean
    ApplicationRunner seedDevelopmentMembers(MemberRepository members) {
        return args -> {
            addIfMissing(members, "민수", "백엔드팀", "U-DEV-MINSU");
            addIfMissing(members, "지영", "백엔드팀", "U-DEV-JIYOUNG-BACKEND");
            addIfMissing(members, "지영", "디자인팀", "U-DEV-JIYOUNG-DESIGN");
            addIfMissing(members, "해찬", "백엔드팀", "U-DEV-HAECHAN");
        };
    }

    private void addIfMissing(MemberRepository members, String name, String team, String slackUserId) {
        if (!members.existsBySlackUserId(slackUserId)) {
            members.save(new Member(name, team, slackUserId));
        }
    }
}
