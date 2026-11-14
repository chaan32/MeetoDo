package com.meetodo.slack;

import com.meetodo.audio.AudioMeetingService;
import com.slack.api.methods.MethodsClient;
import com.slack.api.model.event.MessageEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlackAudioConfigurationTests {
    @Test void audioHandlersAreRegisteredOnConfiguredBoltApp() {
        // 실제 구성 함수가 음성 핸들러를 Bolt에 연결하며 SDK 네트워크는 실행하지 않는다.
        var input = mock(SlackAudioInputService.class);
        var providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(input);
        var app = new SlackBotConfiguration().slackBoltApp(new MockEnvironment().withProperty("SLACK_BOT_TOKEN", "test-token"),
                mock(SlackBotService.class), mock(ObjectProvider.class), mock(SlackTaskEditService.class),
                mock(SlackHomeService.class), mock(SlackMemberManagementService.class), providers, mock(ObjectProvider.class));
        verify(input).register(app);
    }

    @Test void audioBeansRequireOpenaiProfile() throws Exception {
        // 기존 슬랙 기능이 켜져도 openai 없이 음성 다운로드·처리 빈을 만들지 않는다.
        for (var method : SlackBotConfiguration.class.getDeclaredMethods()) {
            if (List.of("slackAudioInputService", "slackAudioDownloader").contains(method.getName())) {
                assertThat(method.getAnnotation(org.springframework.context.annotation.Profile.class).value()).containsExactly("openai");
                assertThat(method.getAnnotation(org.springframework.context.annotation.Conditional.class).value())
                        .contains(SlackBotConfiguration.BotNotifierConfigured.class);
            }
        }
    }
}
