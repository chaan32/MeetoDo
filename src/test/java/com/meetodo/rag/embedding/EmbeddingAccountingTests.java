package com.meetodo.rag.embedding;

import com.meetodo.usage.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class EmbeddingAccountingTests {
    private UsageCall embedding(long input) {
        return UsageCall.raw(1, "embedding-model", input, null, input, null, "embedding");
    }
    private MockEnvironment prices() {
        return new MockEnvironment().withProperty("MEETODO_PRICE_EMBEDDING_MODEL", "embedding-model")
                .withProperty("MEETODO_PRICE_EMBEDDING_INPUT_PER_MILLION", "2");
    }
    @Test void sumsEmbeddingTokensWithoutInventingOutputTokens() {
        // 임베딩에는 출력 토큰 개념을 만들지 않고 실제 입력·합계만 합산한다.
        var report = new UsageReport(List.of(), null, List.of(embedding(123)));
        assertThat(report.inputTokens()).isEqualTo(123L);
        assertThat(report.totalTokens()).isEqualTo(123L);
        assertThat(report.outputTokens()).isNull();
        assertThat(report.usageStatus()).isEqualTo("AVAILABLE");
    }
    @Test void configuredEmbeddingRateProducesCostFromOnlyInputTokens() {
        // 임베딩 단가는 채팅/전사 단가와 분리하고 USD/백만 입력 토큰을 사용한다.
        var report = new UsagePricing(prices()).price(new UsageReport(List.of(), null, List.of(embedding(100))));
        assertThat(report.estimatedCostUsd()).isEqualByComparingTo("0.0002");
        assertThat(report.embeddingCalls().getFirst().costStatus()).isEqualTo("ESTIMATED");
    }
    @Test void missingEmbeddingPricePreservesUsageWithNullCost() {
        // 미설정 단가를 0원으로 추정하지 않는다.
        var report = new UsagePricing(new MockEnvironment()).price(new UsageReport(List.of(), null, List.of(embedding(100))));
        assertThat(report.inputTokens()).isEqualTo(100L);
        assertThat(report.estimatedCostUsd()).isNull();
        assertThat(report.costStatus()).isEqualTo("PRICE_NOT_CONFIGURED");
    }
    @Test void modelMismatchDoesNotReuseEmbeddingRate() {
        // 응답 모델명 날짜 접미사까지 정확히 같지 않으면 가격을 적용하지 않는다.
        var call = UsageCall.raw(1, "embedding-model-next", 100L, null, 100L, null, "embedding");
        var report = new UsagePricing(prices()).price(new UsageReport(List.of(), null, List.of(call)));
        assertThat(report.embeddingCalls()).hasSize(1);
        assertThat(report.embeddingCalls().getFirst().costStatus()).isEqualTo("PRICE_NOT_CONFIGURED");
    }
    @Test void missingEmbeddingUsageIsDifferentFromReportedZero() {
        // 공급자 누락과 실제 0 보고는 서로 다른 상태다.
        var missing = UsageCall.raw(1, "embedding-model", null, null, null, null, "unavailable");
        assertThat(new UsageReport(List.of(), null, List.of(missing)).usageStatus()).isEqualTo("UNAVAILABLE");
        var zero = new UsagePricing(prices()).price(new UsageReport(List.of(), null, List.of(embedding(0))));
        assertThat(zero.usageStatus()).isEqualTo("AVAILABLE");
        assertThat(zero.estimatedCostUsd()).isEqualByComparingTo("0");
    }
    @Test void reportConverterKeepsEmbeddingCallsAndReadsOldJson() {
        // 기존 2필드 JSON은 빈 임베딩 목록으로 읽고 신규 기록은 손실 없이 왕복한다.
        var converter = new UsageReportConverter();
        assertThat(converter.convertToEntityAttribute("{\"chatCalls\":[],\"transcription\":null}").embeddingCalls()).isEmpty();
        var report = new UsagePricing(prices()).price(new UsageReport(List.of(), null, List.of(embedding(42))));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(report))).isEqualTo(report);
    }
}
