package com.meetodo.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackBotConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SlackBotConfiguration.class);
    @Test void defaultConfigurationNeverCreatesSdkClient() {
        // 기본 실행은 토큰이 없어도 시작하고 Slack에 접속하지 않는다.
        runner.withPropertyValues("spring.profiles.active=local").run(context -> assertThat(context).doesNotHaveBean(MethodsClient.class));
    }
    @Test void missingAppTokenDisablesBot() {
        // 활성화 플래그와 봇 토큰만으로 소켓을 열지 않는다.
        runner.withPropertyValues("spring.profiles.active=local", "MEETODO_SLACK_BOT_ENABLED=true", String.join("", "SLACK_BOT_TOKEN=xox", "b-test"))
                .run(context -> assertThat(context).doesNotHaveBean(MethodsClient.class));
    }
    @Test void missingBotTokenDisablesBot() {
        // 앱 토큰만 있는 설정도 네트워크 클라이언트를 만들지 않는다.
        runner.withPropertyValues("spring.profiles.active=docker", "MEETODO_SLACK_BOT_ENABLED=true", String.join("", "SLACK_APP_TOKEN=xap", "p-test"))
                .run(context -> assertThat(context).doesNotHaveBean(MethodsClient.class));
    }
    @Test void testProfileBlocksEvenCompleteCredentials() {
        // 테스트 프로필은 모든 플래그와 토큰이 있어도 실제 Slack 접속을 차단한다.
        runner.withPropertyValues("spring.profiles.active=local,test", "MEETODO_SLACK_BOT_ENABLED=true", String.join("", "SLACK_APP_TOKEN=xap", "p-test"), String.join("", "SLACK_BOT_TOKEN=xox", "b-test"))
                .run(context -> assertThat(context).doesNotHaveBean(MethodsClient.class));
    }
    @Test void validTokensStillRequireExplicitEnable() {
        // 토큰이 상속되어도 명시적인 활성화 없이는 접속하지 않는다.
        assertThat(SlackBotConfiguration.enabled(new MockEnvironment().withProperty("SLACK_APP_TOKEN", String.join("", "xap", "p-test"))
                .withProperty("SLACK_BOT_TOKEN", String.join("", "xox", "b-test")))).isFalse();
    }
    @Test void validExplicitConfigurationPassesCredentialGate() {
        // 유효한 접두사와 명시적인 활성화가 함께 있어야 게이트를 통과한다.
        assertThat(SlackBotConfiguration.enabled(new MockEnvironment().withProperty("MEETODO_SLACK_BOT_ENABLED", "true")
                .withProperty("SLACK_APP_TOKEN", String.join("", "xap", "p-test")).withProperty("SLACK_BOT_TOKEN", String.join("", "xox", "b-test")))).isTrue();
    }
    @Test void socketLifecycleStartsAsynchronouslyAndCloses() throws Exception {
        // 애플리케이션 시작은 비동기 시작 API를 사용하고 종료 시 연결을 정리한다.
        SocketModeApp socket = mock(SocketModeApp.class);
        var runtime = new SlackBotSocketRuntime(socket);
        runtime.start(); runtime.close();
        verify(socket).startAsync(); verify(socket).close();
    }
    @Test void dmFlagDefaultsToDisabledOnConfiguredService() {
        // 봇이 켜져 있어도 DM 설정 생략 시 별도 전송 기능을 켜지 않는다.
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(configuredBot(new MockEnvironment()), "dmEnabled")).isEqualTo(false);
    }
    @Test void explicitDmFlagIsWiredToService() {
        // 환경변수의 명시적 opt-in 값이 실제 서비스에 전달된다.
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(configuredBot(
                new MockEnvironment().withProperty("MEETODO_SLACK_DM_ENABLED", "true")), "dmEnabled")).isEqualTo(true);
    }
    private SlackBotService configuredBot(MockEnvironment env) {
        return new SlackBotConfiguration().slackBotService(mock(MethodsClient.class), env,
                mock(SlackMessageReferenceRepository.class), mock(com.meetodo.task.TaskRepository.class),
                mock(com.meetodo.task.TaskService.class), mock(com.meetodo.meeting.MeetingResults.class),
                mock(com.meetodo.meeting.MeetingRepository.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

}
