package com.meetodo.audio;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class AudioOpenAiConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AudioOpenAiConfiguration.class)
            .withPropertyValues("OPENAI_API_KEY=");

    @Test void missingKeyDisablesTranscriber() {
        // OpenAI 프로필이어도 키가 비어 있으면 실제 전송 빈을 만들지 않는다.
        runner.withPropertyValues("spring.profiles.active=local,openai")
                .run(context -> assertThat(context).doesNotHaveBean(SpeechTranscriber.class));
    }

    @Test void localProfileAloneDisablesTranscriber() {
        // 키가 상속되어도 openai 프로필을 명시해야 한다.
        configured("local").run(context -> assertThat(context).doesNotHaveBean(SpeechTranscriber.class));
    }

    @Test void openaiProfileAloneDisablesTranscriber() {
        // 허용한 실행 환경 없이 openai 프로필만 켜도 전송 빈을 만들지 않는다.
        configured("openai").run(context -> assertThat(context).doesNotHaveBean(SpeechTranscriber.class));
    }

    @Test void testProfileBlocksConfiguredTranscriber() {
        // test가 포함되면 키와 활성 프로필이 모두 있어도 외부 전송을 차단한다.
        configured("local,openai,test").run(context -> assertThat(context).doesNotHaveBean(SpeechTranscriber.class));
    }

    @Test void localOpenaiUsesDefaultModelWithoutNetworkCall() {
        // 빈 생성만으로 네트워크를 호출하지 않고 기본 모델을 선택한다.
        configured("local,openai").run(context -> {
            assertThat(context).hasSingleBean(SpeechTranscriber.class);
            assertThat(context.getBean(SpeechTranscriber.class).model()).isEqualTo("gpt-transcribe");
        });
    }

    @Test void dockerOpenaiUsesConfiguredModel() {
        // Docker 실행도 같은 어댑터를 쓰고 환경변수로 모델만 바꾼다.
        configured("docker,openai").withPropertyValues("MEETODO_STT_MODEL=custom-transcribe-2")
                .run(context -> assertThat(context.getBean(SpeechTranscriber.class).model()).isEqualTo("custom-transcribe-2"));
    }

    @Test void configuredTransportHasBoundedTimeouts() {
        // 실제 HTTP 전송 팩토리에 연결 3초·읽기 60초 제한이 적용된다.
        configured("local,openai").run(context -> {
            Object client = ReflectionTestUtils.getField(context.getBean(OpenAiAudioApi.class), "restClient");
            Object factory = ReflectionTestUtils.getField(client, "clientRequestFactory");
            assertThat(ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3_000);
            assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(60_000);
        });
    }

    private ApplicationContextRunner configured(String profiles) {
        return runner.withPropertyValues("spring.profiles.active=" + profiles,
                String.join("", "OPENAI_API_KEY=sk-", "unit-test-config"));
    }
}
