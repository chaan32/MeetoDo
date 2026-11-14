package com.meetodo.member;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthorization {
    private final Set<String> admins;
    public AdminAuthorization(@Value("${MEETODO_ADMIN_SLACK_IDS:}") String configuredIds) {
        admins = Arrays.stream(configuredIds.split(",")).map(String::trim)
                .filter(value -> value.matches("[UW][A-Z0-9]{1,79}")).collect(Collectors.toUnmodifiableSet());
    }
    public boolean isAdmin(String slackUserId) { return slackUserId != null && admins.contains(slackUserId); }
    public Set<String> ids() { return admins; }
    public void requireAdmin(String slackUserId) {
        if (!isAdmin(slackUserId)) throw new MemberManagementException(HttpStatus.FORBIDDEN,
                "ADMIN_REQUIRED", "관리자만 팀원을 관리할 수 있어요.");
    }
}
