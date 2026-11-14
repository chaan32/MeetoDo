package com.meetodo.config;

import java.util.Map;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SelfHostingConfigurationTests {
    private static final AtomicInteger CREATED = new AtomicInteger();

    @BeforeEach void resetBeanProbe() { CREATED.set(0); }

    // Boot 등록 누락이나 ConfigData 이전 실행은 실제 시작 단계에서 검증을 무력화한다.
    @Test void registeredProcessorRunsAfterConfigData() {
        assertThat(((Ordered) processor()).getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    // slack-bot 선택 시 필요한 세 설정을 한 번의 오류에서 모두 알려준다.
    @Test void slackBotListsAllMissingNames() {
        assertThatThrownBy(() -> validate(environment("local", Map.of("NOTIFIER_TYPE", "slack-bot"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID");
    }

    // 공백 값도 누락으로 처리하며 notifier의 기존 대소문자·공백 허용을 유지한다.
    @Test void blankSlackSettingsAreMissingForCaseInsensitiveNotifier() {
        assertThatThrownBy(() -> validate(environment("docker", Map.of("NOTIFIER_TYPE", " SLACK-BOT ",
                "SLACK_BOT_TOKEN", " \t", "SLACK_APP_TOKEN", "\n", "SLACK_CHANNEL_ID", " "))))
                .hasMessage("필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID");
    }

    // 일부 설정만 누락되면 이미 제공된 설정의 이름과 값은 출력하지 않는다.
    @Test void partialSlackConfigurationListsOnlyMissingName() {
        assertThatThrownBy(() -> validate(environment("local", Map.of("NOTIFIER_TYPE", "slack-bot",
                "SLACK_BOT_TOKEN", "private-bot", "SLACK_CHANNEL_ID", "private-channel"))))
                .hasMessage("필수 환경변수가 없습니다: SLACK_APP_TOKEN");
    }

    // 형식 검증은 기존 SDK 활성화 조건에 맡기고 여기서는 값의 존재만 검증한다.
    @Test void presentSlackSettingsPassWithoutExtraFormatValidation() {
        assertThatCode(() -> validate(environment("local", Map.of("NOTIFIER_TYPE", "slack-bot",
                "SLACK_BOT_TOKEN", "bot", "SLACK_APP_TOKEN", "app", "SLACK_CHANNEL_ID", "channel"))))
                .doesNotThrowAnyException();
    }

    // openai를 명시했으면 다른 프로필 없이도 API 키가 필요하다.
    @Test void openaiProfileRequiresApiKey() {
        assertThatThrownBy(() -> validate(environment("openai", Map.of())))
                .hasMessage("필수 환경변수가 없습니다: OPENAI_API_KEY");
    }

    // 빈 문자열과 공백 API 키는 SDK 생성 전에 거절한다.
    @Test void blankOpenaiApiKeyIsMissing() {
        assertThatThrownBy(() -> validate(environment("local,openai", Map.of("OPENAI_API_KEY", " \t"))))
                .hasMessage("필수 환경변수가 없습니다: OPENAI_API_KEY");
    }

    // 키가 존재하면 실제 모델을 만들거나 외부 호출을 하지 않고 검증을 통과한다.
    @Test void presentOpenaiApiKeyPasses() {
        assertThatCode(() -> validate(environment("docker,openai", Map.of("OPENAI_API_KEY", "private-openai"))))
                .doesNotThrowAnyException();
    }

    // Slack과 OpenAI의 누락 항목은 서로 덮어쓰지 않고 함께 보고한다.
    @Test void combinedModeListsEveryMissingName() {
        assertThatThrownBy(() -> validate(environment("docker,openai", Map.of("NOTIFIER_TYPE", "slack-bot"))))
                .hasMessage("필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID, OPENAI_API_KEY");
    }

    // 기본 프로필은 외부 전송을 선택하지 않으므로 비밀값이 없어도 시작할 수 있다.
    @Test void defaultProfileNeedsNoCredentials() {
        assertThatCode(() -> validate(environment("", Map.of()))).doesNotThrowAnyException();
    }

    // local 개발도 AI와 notifier를 선택하지 않으면 키 없이 사용할 수 있다.
    @Test void localProfileNeedsNoCredentials() {
        assertThatCode(() -> validate(environment("local", Map.of("NOTIFIER_TYPE", "none")))).doesNotThrowAnyException();
    }

    // 웹훅 방식은 이 단계의 봇 토큰 검사 대상이 아니다.
    @Test void webhookNotifierDoesNotRequireBotCredentials() {
        assertThatCode(() -> validate(environment("local", Map.of("NOTIFIER_TYPE", "slack")))).doesNotThrowAnyException();
    }

    // test가 함께 있으면 실제 전송 빈과 마찬가지로 비밀값 요구도 비활성화한다.
    @Test void testProfileSkipsAllCredentialRequirements() {
        assertThatCode(() -> validate(environment("docker,openai,test", Map.of("NOTIFIER_TYPE", "slack-bot"))))
                .doesNotThrowAnyException();
    }

    // 실제 Boot 경로가 ConfigData의 활성 프로필을 읽고 빈 초기화 이전에 중단하는지 확인한다.
    @Test void bootstrapFailsBeforeBeanCreationAfterLoadingProfiles() {
        SpringApplication app = application(environment("", Map.of()));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=local,openai"))
                .hasMessage("필수 환경변수가 없습니다: OPENAI_API_KEY");
        assertThat(CREATED).hasValue(0);
    }

    // Boot의 실패 로그와 예외에도 이미 입력된 비밀값·채널을 출력하지 않는다.
    @Test void bootstrapFailureLogsOnlyMissingNames(CapturedOutput output) {
        SpringApplication app = application(environment("", Map.of("NOTIFIER_TYPE", "slack-bot",
                "SLACK_BOT_TOKEN", "private-bot-token", "SLACK_CHANNEL_ID", "private-channel-id",
                "OPENAI_API_KEY", "private-openai-key")));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=local,openai"))
                .hasMessage("필수 환경변수가 없습니다: SLACK_APP_TOKEN");
        assertThat(output.getAll()).contains("SLACK_APP_TOKEN")
                .doesNotContain("private-bot-token", "private-channel-id", "private-openai-key");
        assertThat(CREATED).hasValue(0);
    }

    // test 프로필은 실제 Boot 경로에서도 외부 SDK 없이 정상 시작한다.
    @Test void testBootstrapDoesNotRequireSelectedTransportCredentials() {
        try (var context = application(environment("", Map.of("NOTIFIER_TYPE", "slack-bot")))
                .run("--spring.profiles.active=local,openai,test")) {
            assertThat(context.isActive()).isTrue();
            assertThat(CREATED).hasValue(1);
        }
    }

    // 기본 local 시작 경로는 기존의 키 없는 개발 흐름을 유지한다.
    @Test void defaultBootstrapStillStartsWithoutCredentials() {
        try (var context = application(environment("", Map.of())).run()) {
            assertThat(context.getEnvironment().getDefaultProfiles()).contains("local");
            assertThat(CREATED).hasValue(1);
        }
    }

    // 전용 예외는 누락 이름만 보관하고 Analyzer가 정확한 안내 문구를 만든다.
    @Test void analyzerExplainsMissingNameAndConfigurationAction() {
        FailureAnalysis analysis = analyzer().analyze(missing(environment("local", Map.of(
                "NOTIFIER_TYPE", "slack-bot", "SLACK_BOT_TOKEN", "present", "SLACK_APP_TOKEN", "present"))));
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("필수 환경변수가 없습니다: SLACK_CHANNEL_ID");
        assertThat(analysis.getAction()).isEqualTo(".env.example을 참고해 값을 설정하세요.");
    }

    // 여러 누락 값은 한 번의 분석 결과에 기존 검사 순서대로 모두 표시한다.
    @Test void analyzerListsEveryMissingVariableTogether() {
        FailureAnalysis analysis = analyzer().analyze(missing(environment("local,openai", Map.of("NOTIFIER_TYPE", "slack-bot"))));
        assertThat(analysis.getDescription()).isEqualTo(
                "필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID, OPENAI_API_KEY");
    }

    // 일반 예외까지 이 설치 오류로 바꾸면 실제 장애 원인을 가리므로 처리하지 않는다.
    @Test void analyzerLeavesUnrelatedFailuresToOtherAnalyzers() {
        assertThat(analyzer().analyze(new IllegalStateException("unrelated"))).isNull();
    }

    // 실제 환경 준비 단계 실패는 컨텍스트를 생성하기 전에 표준 화면만 출력한다.
    @Test void bootstrapReportsStandardFailureBeforeContextCreation(CapturedOutput output) {
        var contexts = new AtomicInteger();
        var app = application(environment("", Map.of("NOTIFIER_TYPE", "slack-bot")));
        app.setApplicationContextFactory(type -> {
            contexts.incrementAndGet();
            return new org.springframework.context.support.GenericApplicationContext();
        });
        assertThatThrownBy(() -> app.run("--spring.profiles.active=local"))
                .hasMessage("필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID");
        assertThat(contexts).hasValue(0);
        assertThat(CREATED).hasValue(0);
        assertThat(output.getAll()).contains("APPLICATION FAILED TO START", "Description:", "Action:",
                "필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID",
                ".env.example을 참고해 값을 설정하세요.")
                .doesNotContain("Application run failed", "IllegalStateException", "\tat ", "Exception in thread");
    }

    // 다른 변수에 토큰 형태 문자열이 있어도 예외·표준 화면은 이름만 사용한다.
    @Test void bootstrapFailureNeverPrintsTokenShapedValues(CapturedOutput output) {
        String bot = String.join("", "xox", "b-", "123456789012-123456789012-", "a".repeat(24));
        String appToken = String.join("", "xap", "p-1-", "A12345678-", "1234567890123-", "b".repeat(32));
        String key = String.join("", "s", "k-", "c".repeat(48));
        var app = application(environment("", Map.of("NOTIFIER_TYPE", "slack-bot",
                "SLACK_BOT_TOKEN", bot, "SLACK_APP_TOKEN", appToken, "OPENAI_API_KEY", key)));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=local,openai"))
                .hasMessage("필수 환경변수가 없습니다: SLACK_CHANNEL_ID")
                .hasMessageNotContaining(bot).hasMessageNotContaining(appToken).hasMessageNotContaining(key);
        assertThat(output.getAll()).contains("APPLICATION FAILED TO START")
                .doesNotContain(bot, appToken, key, "Application run failed", "\tat ");
        assertThat(CREATED).hasValue(0);
    }

    // 명시적 빈 값과 공백도 미설정과 같은 화면에서 누락으로 표시한다.
    @Test void bootstrapReportsEmptyAndWhitespaceValuesAsMissing(CapturedOutput output) {
        var app = application(environment("", Map.of("NOTIFIER_TYPE", "slack-bot",
                "SLACK_BOT_TOKEN", "", "SLACK_APP_TOKEN", "  ", "SLACK_CHANNEL_ID", "\t")));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=local"))
                .hasMessage("필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID");
        assertThat(output.getAll()).contains("APPLICATION FAILED TO START",
                "필수 환경변수가 없습니다: SLACK_BOT_TOKEN, SLACK_APP_TOKEN, SLACK_CHANNEL_ID");
    }

    private RuntimeException missing(MockEnvironment environment) {
        try { validate(environment); }
        catch (RuntimeException failure) { return failure; }
        throw new AssertionError("Expected missing configuration");
    }

    private FailureAnalyzer analyzer() {
        String name = "com.meetodo.config.MissingRequiredEnvironmentFailureAnalyzer";
        assertThat(SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, getClass().getClassLoader()))
                .contains(name);
        try { return (FailureAnalyzer) Class.forName(name).getConstructor().newInstance(); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private EnvironmentPostProcessor processor() {
        String className = "com.meetodo.config.SelfHostingEnvironmentPostProcessor";
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(className);
        try {
            return (EnvironmentPostProcessor) Class.forName(className).getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot instantiate self-hosting EnvironmentPostProcessor", exception);
        }
    }

    private void validate(MockEnvironment environment) {
        processor().postProcessEnvironment(environment, new SpringApplication(ProbeConfiguration.class));
    }

    private MockEnvironment environment(String profiles, Map<String, String> properties) {
        MockEnvironment environment = new MockEnvironment();
        if (!profiles.isEmpty()) environment.setActiveProfiles(profiles.split(","));
        properties.forEach(environment::setProperty);
        return environment;
    }

    private SpringApplication application(MockEnvironment environment) {
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        return application;
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean String creationProbe() {
            CREATED.incrementAndGet();
            return "created";
        }
    }
}
