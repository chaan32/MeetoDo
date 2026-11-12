package com.meetodo.notification;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SlackErrorCodeTests {
    @Test void preservesMissingScopeCodeForDiagnosis() {
        // Slack 권한 오류를 일반 오류로 덮지 않아 운영자가 원인을 구분한다.
        assertThat(NotificationStateService.safeCode("missing_scope")).isEqualTo("missing_scope");
    }
    @Test void preservesKnownChannelAndAuthenticationCodes() {
        // Slack의 채널·인증 오류 식별자는 비밀값 없이 그대로 보존한다.
        assertThat(NotificationStateService.safeCode("not_in_channel")).isEqualTo("not_in_channel");
        assertThat(NotificationStateService.safeCode("channel_not_found")).isEqualTo("channel_not_found");
        assertThat(NotificationStateService.safeCode("invalid_auth")).isEqualTo("invalid_auth");
    }
    @Test void rejectsProviderTextAndSecretsInsteadOfLoggingThem() {
        // 임의 문자열·토큰·URL·예외 원문은 오류 코드로 저장하지 않는다.
        for (String unsafe : new String[]{"xoxb-secret", "xapp-secret", "https://hooks.slack.com/secret", "missing_scope xoxb-secret", "provider_secret"})
            assertThat(NotificationStateService.safeCode(unsafe)).isEqualTo("INTERNAL_ERROR");
    }
    @Test void keepsExistingWebhookErrorCodes() {
        // 기존 웹훅 전송 실패 코드는 변경하지 않는다.
        assertThat(NotificationStateService.safeCode("HTTP_ERROR")).isEqualTo("HTTP_ERROR");
        assertThat(NotificationStateService.safeCode("TIMEOUT_DELIVERY_UNKNOWN")).isEqualTo("TIMEOUT_DELIVERY_UNKNOWN");
    }
    @Test void preservesDocumentedWorkspaceAccessError() {
        // 공식 Slack 오류 목록에 있는 워크스페이스 접근 오류도 진단 근거로 보존한다.
        assertThat(NotificationStateService.safeCode("team_access_not_granted")).isEqualTo("team_access_not_granted");
    }
    @Test void preservesDocumentedSlackServiceUnavailableError() {
        // Slack 서비스 가용성 오류를 내부 예외와 구분한다.
        assertThat(NotificationStateService.safeCode("service_unavailable")).isEqualTo("service_unavailable");
    }
    @Test void preservesDocumentedDisabledMessagesTabError() {
        // App Home Messages Tab 설정 오류를 원래 코드로 안내한다.
        assertThat(NotificationStateService.safeCode("messages_tab_disabled")).isEqualTo("messages_tab_disabled");
    }

}
