package com.meetodo.member;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdminAuthorizationTests {
    @Test void parsesCommaSeparatedAdministratorsAndTrimsConfigurationWhitespace() {
        // 설정의 쉼표와 공백을 처리해 명시한 관리자만 허용한다.
        var authorization = new AdminAuthorization(" UADMIN, USECOND , UADMIN ");
        assertThat(authorization.isAdmin("UADMIN")).isTrue();
        assertThat(authorization.isAdmin("USECOND")).isTrue();
        assertThat(authorization.isAdmin("UOTHER")).isFalse();
    }
    @Test void emptyConfigurationDoesNotGrantDefaultAdministrator() {
        // 설정이 없는 실행에는 기본 관리자 권한을 부여하지 않는다.
        var authorization = new AdminAuthorization("");
        assertThat(authorization.isAdmin("UADMIN")).isFalse();
        assertThatThrownBy(() -> authorization.requireAdmin("UADMIN"))
                .isInstanceOfSatisfying(MemberManagementException.class, error -> assertThat(error.getCode()).isEqualTo("ADMIN_REQUIRED"));
    }
    @Test void missingActorNeverReceivesAdministratorPermission() {
        // 누락된 사용자 정보를 관리자로 추측하지 않는다.
        assertThat(new AdminAuthorization("UADMIN").isAdmin(null)).isFalse();
    }
    @Test void malformedConfiguredIdentifiersAreNotAdministrators() {
        // Slack ID 형식이 아닌 환경변수 항목은 권한으로 사용하지 않는다.
        var authorization = new AdminAuthorization("U-DEV-ADMIN,admin, UVALID");
        assertThat(authorization.isAdmin("U-DEV-ADMIN")).isFalse();
        assertThat(authorization.isAdmin("admin")).isFalse();
        assertThat(authorization.isAdmin("UVALID")).isTrue();
    }
}
