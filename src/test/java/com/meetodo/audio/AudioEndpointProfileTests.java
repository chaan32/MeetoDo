package com.meetodo.audio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AudioEndpointProfileTests {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(AudioMeetingController.class)
            .withBean(AudioMeetingService.class, () -> mock(AudioMeetingService.class));

    // 기본·local·test 실행에는 오디오 HTTP 진입점이 생성되지 않는다.
    @ParameterizedTest @ValueSource(strings={"","local","test"})
    void nonOpenaiProfileDoesNotLoadAudioEndpoint(String profile) {
        runner.withPropertyValues("spring.profiles.active="+profile)
                .run(context -> assertThat(context).doesNotHaveBean(AudioMeetingController.class));
    }

    // openai 테스트는 HTTP 계약을 검증할 수 있지만 실제 전사 클라이언트는 만들지 않는다.
    @Test void openaiProfileLoadsOnlyEndpointWithMockService() {
        runner.withPropertyValues("spring.profiles.active=test,openai").run(context -> {
            assertThat(context).hasSingleBean(AudioMeetingController.class);
            assertThat(context).doesNotHaveBean(SpeechTranscriber.class);
        });
    }
}
