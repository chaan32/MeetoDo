package com.meetodo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.digest.DailyDigestController;
import com.meetodo.digest.DailyDigestService;
import com.meetodo.notification.NotificationConfiguration;
import com.meetodo.notification.Notifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DockerProfileConditionsTests {
    ApplicationContextRunner dev = new ApplicationContextRunner().withUserConfiguration(DailyDigestController.class)
            .withBean(DailyDigestService.class, () -> mock(DailyDigestService.class));
    ApplicationContextRunner notifications = new ApplicationContextRunner().withUserConfiguration(NotificationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    // Docker 기본 설정은 개발용 수동 알림 API를 노출하지 않는다.
    @Test void dockerDevEndpointsAreDisabledByDefault() {
        dev.withPropertyValues("spring.profiles.active=docker").run(c -> assertThat(c).doesNotHaveBean(DailyDigestController.class));
    }
    // Docker 데모 플래그를 명시했을 때만 수동 실행 API를 노출한다.
    @Test void explicitDockerDemoFlagEnablesDigestEndpoint() {
        dev.withPropertyValues("spring.profiles.active=docker", "meetodo.dev.endpoints-enabled=true")
                .run(c -> assertThat(c).hasSingleBean(DailyDigestController.class));
    }
    // 기존 local 개발 API는 플래그 없이도 그대로 사용할 수 있다.
    @Test void localEndpointRemainsEnabledWithoutFlag() {
        dev.withPropertyValues("spring.profiles.active=local").run(c -> assertThat(c).hasSingleBean(DailyDigestController.class));
    }
    // 다른 운영 프로필에 데모 플래그만 켜도 개발 API가 생기지 않는다.
    @Test void demoFlagDoesNotExposeEndpointOutsideLocalOrDocker() {
        dev.withPropertyValues("spring.profiles.active=prod", "meetodo.dev.endpoints-enabled=true")
                .run(c -> assertThat(c).doesNotHaveBean(DailyDigestController.class));
    }
    // Docker도 OpenAI 없이 설정된 메신저 빈을 생성하며 실제 전송은 하지 않는다.
    @Test void dockerCanConfigureNotifierWithoutOpenai() {
        notifications.withPropertyValues("spring.profiles.active=docker", "NOTIFIER_TYPE=discord", String.join("", "DISCORD_WEBHOOK_URL=https", "://discord.com/api/webhooks/fake"))
                .run(c -> assertThat(c).hasSingleBean(Notifier.class));
    }
    // test 프로필은 Docker와 함께 지정해도 실제 메신저 전송 빈을 제외한다.
    @Test void dockerTestProfileNeverCreatesRealNotifier() {
        notifications.withPropertyValues("spring.profiles.active=docker,test", "NOTIFIER_TYPE=discord", String.join("", "DISCORD_WEBHOOK_URL=https", "://discord.com/api/webhooks/fake"))
                .run(c -> assertThat(c).doesNotHaveBean(Notifier.class));
    }
    private ApplicationContextRunner aiContext() {
        return new ApplicationContextRunner().withUserConfiguration(com.meetodo.meeting.SpringAiMeetingAgent.class)
                .withBean(org.springframework.ai.chat.model.ChatModel.class, () -> mock(org.springframework.ai.chat.model.ChatModel.class))
                .withBean(org.springframework.ai.model.tool.ToolCallingManager.class, () -> mock(org.springframework.ai.model.tool.ToolCallingManager.class))
                .withBean(com.meetodo.tools.FindMemberTool.class, () -> mock(com.meetodo.tools.FindMemberTool.class))
                .withBean(com.meetodo.tools.FindTasksTool.class, () -> mock(com.meetodo.tools.FindTasksTool.class))
                .withBean(com.meetodo.tools.CreateTaskTool.class, () -> mock(com.meetodo.tools.CreateTaskTool.class))
                .withBean(com.meetodo.tools.ToolExecutionService.class, () -> mock(com.meetodo.tools.ToolExecutionService.class))
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC);
    }

    // Docker 단독은 키 없는 개발이 가능하도록 회의 처리 에이전트를 생성하지 않는다.
    @Test void dockerOnlyDoesNotEnableMeetingAgent() {
        aiContext().withPropertyValues("spring.profiles.active=docker")
                .run(c -> assertThat(c).doesNotHaveBean(com.meetodo.meeting.SpringAiMeetingAgent.class));
    }
    // OpenAI를 명시한 Docker에서만 에이전트를 생성하고 모델은 mock이라 실제 호출하지 않는다.
    @Test void explicitDockerOpenaiEnablesMeetingAgentWithMockModel() {
        aiContext().withPropertyValues("spring.profiles.active=docker,openai")
                .run(c -> assertThat(c).hasSingleBean(com.meetodo.meeting.SpringAiMeetingAgent.class));
    }
    // test는 docker와 openai를 함께 지정해도 실제 에이전트 빈을 제외한다.
    @Test void dockerOpenaiTestDoesNotEnableMeetingAgent() {
        aiContext().withPropertyValues("spring.profiles.active=docker,openai,test")
                .run(c -> assertThat(c).doesNotHaveBean(com.meetodo.meeting.SpringAiMeetingAgent.class));
    }

}
