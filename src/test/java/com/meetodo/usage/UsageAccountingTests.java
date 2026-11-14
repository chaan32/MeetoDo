package com.meetodo.usage;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class UsageAccountingTests {
    private UsageCall tokens(int order, long input, long output) {
        return UsageCall.raw(order, "chat-model", input, output, input + output, null, "tokens");
    }
    private MockEnvironment prices() {
        return new MockEnvironment().withProperty("MEETODO_PRICE_CHAT_MODEL", "chat-model")
                .withProperty("MEETODO_PRICE_CHAT_INPUT_PER_MILLION", "2")
                .withProperty("MEETODO_PRICE_CHAT_OUTPUT_PER_MILLION", "8");
    }
    @Test void sumsEveryCallWithoutCountingToolExecutionsAsModelCalls() {
        // 각 모델 응답의 입력·출력·합계와 호출 순번을 보존한다.
        UsageReport report = new UsageReport(List.of(tokens(1, 100, 20), tokens(2, 200, 30)), null);
        assertThat(report.inputTokens()).isEqualTo(300L);
        assertThat(report.outputTokens()).isEqualTo(50L);
        assertThat(report.totalTokens()).isEqualTo(350L);
        assertThat(report.chatCalls()).extracting(UsageCall::callOrder).containsExactly(1, 2);
        assertThat(report.usageStatus()).isEqualTo("AVAILABLE");
    }
    @Test void configuredExactModelRatesProduceDecimalCostSnapshot() {
        // 공식 단가를 코드에 넣지 않고 설정한 USD 단가로만 비용을 계산한다.
        UsageReport report = new UsagePricing(prices()).price(new UsageReport(List.of(tokens(1, 100, 20)), null));
        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0.00036");
        assertThat(report.costStatus()).isEqualTo("ESTIMATED");
        assertThat(report.chatCalls().getFirst().estimatedCostUsd()).isEqualByComparingTo("0.00036");
    }
    @Test void missingPriceKeepsTokensButCostIsNull() {
        // 단가가 없으면 0원으로 오인하지 않도록 비용 null과 원인을 반환한다.
        UsageReport report = new UsagePricing(new MockEnvironment()).price(new UsageReport(List.of(tokens(1, 100, 20)), null));
        assertThat(report.inputTokens()).isEqualTo(100L);
        assertThat(report.estimatedCostUsd()).isNull();
        assertThat(report.costStatus()).isEqualTo("PRICE_NOT_CONFIGURED");
    }
    @Test void unknownModelDoesNotBorrowAnotherModelsPrice() {
        // 모델 별칭이나 새 버전을 다른 모델의 단가로 추측해서 계산하지 않는다.
        UsageCall other = UsageCall.raw(1, "chat-model-next", 100L, 20L, 120L, null, "tokens");
        assertThat(new UsagePricing(prices()).price(new UsageReport(List.of(other), null)).estimatedCostUsd()).isNull();
    }
    @Test void missingUsageIsDifferentFromReportedZeroTokens() {
        // 공급자가 사용량을 안 준 경우와 실제 0 토큰을 보고한 경우를 구분한다.
        UsageCall absent = UsageCall.raw(1, "chat-model", null, null, null, null, "unavailable");
        assertThat(new UsageReport(List.of(absent), null).inputTokens()).isNull();
        assertThat(new UsageReport(List.of(absent), null).usageStatus()).isEqualTo("UNAVAILABLE");
        assertThat(new UsageReport(List.of(tokens(1, 0, 0)), null).inputTokens()).isZero();
        assertThat(new UsageReport(List.of(tokens(1, 0, 0)), null).usageStatus()).isEqualTo("AVAILABLE");
    }
    @Test void partiallyMissingResponseNeverPretendsFullCostIsKnown() {
        // 일부 호출 사용량이 없으면 확인한 토큰 합계만 남기고 전체 비용은 null이다.
        UsageCall absent = UsageCall.raw(2, "chat-model", null, null, null, null, "unavailable");
        UsageReport report = new UsagePricing(prices()).price(new UsageReport(List.of(tokens(1, 100, 20), absent), null));
        assertThat(report.inputTokens()).isEqualTo(100L);
        assertThat(report.usageStatus()).isEqualTo("PARTIAL");
        assertThat(report.estimatedCostUsd()).isNull();
        assertThat(report.costStatus()).isEqualTo("PARTIAL_USAGE");
    }
    @Test void transcriptionDurationUsesConfiguredMinutePriceOnly() {
        // 응답이 제공한 초 단위 길이를 분 단가에 적용하며 파일 크기로 길이를 추정하지 않는다.
        MockEnvironment env = new MockEnvironment().withProperty("MEETODO_PRICE_STT_MODEL", "audio-model")
                .withProperty("MEETODO_PRICE_STT_PER_MINUTE", "0.12");
        UsageCall audio = UsageCall.raw(1, "audio-model", null, null, null, new BigDecimal("30"), "duration");
        UsageReport report = new UsagePricing(env).price(new UsageReport(List.of(), audio));
        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0.06");
        assertThat(report.transcription().durationSeconds()).isEqualByComparingTo("30");
        assertThat(report.inputTokens()).isNull();
        assertThat(report.usageStatus()).isEqualTo("AVAILABLE");
    }
    @Test void transcriptionTokenUsageUsesItsOwnModelRates() {
        // 음성 토큰 가격과 채팅 토큰 가격을 혼용하지 않는다.
        MockEnvironment env = prices().withProperty("MEETODO_PRICE_STT_MODEL", "audio-model")
                .withProperty("MEETODO_PRICE_STT_INPUT_PER_MILLION", "4")
                .withProperty("MEETODO_PRICE_STT_OUTPUT_PER_MILLION", "10");
        UsageCall audio = UsageCall.raw(1, "audio-model", 100L, 10L, 110L, null, "tokens");
        assertThat(new UsagePricing(env).price(new UsageReport(List.of(), audio)).estimatedCostUsd()).isEqualByComparingTo("0.0005");
    }
    @Test void converterRoundTripsOnlyUsageAndPriceFields() {
        // 공급자 응답 원문·메타데이터 전체를 저장하지 않고 필요한 사용량 값만 직렬화한다.
        var converter = new UsageReportConverter();
        UsageReport report = new UsagePricing(prices()).price(new UsageReport(List.of(tokens(1, 100, 20)), null));
        String json = converter.convertToDatabaseColumn(report);
        assertThat(converter.convertToEntityAttribute(json)).isEqualTo(report);
        assertThat(json).doesNotContain("rawText", "Authorization", "nativeUsage");
    }
    @Test void invalidRateDoesNotExposeConfiguredValue() {
        // 잘못된 단가 설정은 비밀일 수 있는 원문 값을 오류에 포함하지 않는다.
        assertThatThrownBy(() -> new UsagePricing(prices().withProperty("MEETODO_PRICE_CHAT_INPUT_PER_MILLION", "private-setting")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-setting");
    }
    @Test void negativeRateIsRejected() {
        // 음수 단가로 잘못된 예상 비용을 만들 수 없다.
        assertThatThrownBy(() -> new UsagePricing(prices().withProperty("MEETODO_PRICE_CHAT_INPUT_PER_MILLION", "-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
