package com.meetodo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new).withUserConfiguration(NotificationConfiguration.class);

    // 검증: 기본 프로필은 웹훅 환경변수가 있어도 실제 전송 빈을 로드하지 않는다.
    @Test void defaultProfileDoesNotLoadTransport() {
        context.withPropertyValues("NOTIFIER_TYPE=discord", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    // 검증: test 프로필은 local,openai와 함께 지정해도 실제 전송 빈을 제외한다.
    @Test void testProfileAlwaysExcludesTransport() {
        context.withPropertyValues("spring.profiles.active=local,openai,test", "NOTIFIER_TYPE=discord", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    // 검증: 선택 공급자 URL이 없으면 전송 빈 없이 SKIPPED 경로를 사용한다.
    @Test void missingUrlDoesNotLoadTransport() {
        context.withPropertyValues("spring.profiles.active=local,openai", "NOTIFIER_TYPE=discord", "DISCORD_WEBHOOK_URL=")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    // 검증: 선택한 타입의 URL이 있어야 Slack 구현체를 생성한다.
    @Test void slackConfigurationSelectsSlackNotifierWithoutSending() {
        context.withPropertyValues("spring.profiles.active=local,openai", "NOTIFIER_TYPE=slack", "SLACK_WEBHOOK_URL=https://hooks.slack.com/services/fake")
                .run(c -> assertThat(c.getBean(Notifier.class)).isInstanceOf(SlackNotifier.class));
    }
    // 검증: 선택한 타입의 URL이 있어야 Discord 구현체를 생성한다.
    @Test void discordConfigurationSelectsDiscordNotifierWithoutSending() {
        context.withPropertyValues("spring.profiles.active=local,openai", "NOTIFIER_TYPE=discord", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c.getBean(Notifier.class)).isInstanceOf(DiscordNotifier.class));
    }
    // 검증: 다른 공급자의 URL만 있으면 선택 공급자를 활성화하지 않는다.
    @Test void wrongProviderUrlDoesNotEnableTransport() {
        context.withPropertyValues("spring.profiles.active=local,openai", "NOTIFIER_TYPE=slack", "SLACK_WEBHOOK_URL=", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    // 검증: 기본 local 프로필에서도 openai가 없으면 URL이 있어도 전송 빈을 생성하지 않는다.
    @Test void localOnlyProfileDoesNotLoadTransport() {
        context.withPropertyValues("spring.profiles.active=local", "NOTIFIER_TYPE=discord", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    // 검증: 지원하지 않는 공급자 타입은 실제 전송 빈을 만들지 않는다.
    @Test void unsupportedTypeDoesNotLoadTransport() {
        context.withPropertyValues("spring.profiles.active=local,openai", "NOTIFIER_TYPE=other", "DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/fake")
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }

}
