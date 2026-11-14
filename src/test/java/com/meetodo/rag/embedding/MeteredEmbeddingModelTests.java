package com.meetodo.rag.embedding;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.openai.api.OpenAiApi;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MeteredEmbeddingModelTests {
    private final EmbeddingModel provider = mock(EmbeddingModel.class);
    private final EmbeddingUsageCollector collector = new EmbeddingUsageCollector();
    private final MeteredEmbeddingModel model = new MeteredEmbeddingModel(provider, collector, 2);
    private EmbeddingResponse response(Integer input, Integer total) {
        return new EmbeddingResponse(List.of(new Embedding(new float[]{1,0}, 0)),
                new EmbeddingResponseMetadata("actual-model", new DefaultUsage(input, null, total,
                        new OpenAiApi.Usage(null, input, total))));
    }
    @Test void dimensionLookupNeverCallsProvider() {
        // 알려진 차원 조회는 숨은 과금용 HelloWorld 요청을 만들지 않는다.
        assertThat(model.dimensions()).isEqualTo(2);
        verifyNoInteractions(provider);
    }
    @Test void recordsRealModelAndBatchTokenUsageFromCall() {
        // 벡터만 반환하는 편의 API를 사용해도 응답 모델·사용량을 수집한다.
        when(provider.call(any())).thenReturn(response(11, 11));
        try (var scope = collector.open()) {
            assertThat(model.embed(List.of("question"))).hasSize(1);
            assertThat(scope.callCount()).isEqualTo(1);
            var call = scope.report().embeddingCalls().getFirst();
            assertThat(call.model()).isEqualTo("actual-model");
            assertThat(call.inputTokens()).isEqualTo(11L);
            assertThat(call.outputTokens()).isNull();
            assertThat(call.totalTokens()).isEqualTo(11L);
        }
    }
    @Test void documentEmbeddingUsesMeteredCallInsteadOfDelegateConvenienceMethod() {
        // SimpleVectorStore의 단건 Document 경로도 누락 없이 같은 계측 경계를 지난다.
        when(provider.call(any())).thenReturn(response(3, 3));
        try (var scope = collector.open()) {
            assertThat(model.embed(new Document("task"))).containsExactly(1,0);
            assertThat(scope.callCount()).isEqualTo(1);
            verify(provider, never()).embed(any(Document.class));
        }
    }
    @Test void providerFailureRecordsMissingUsageAndReturnsSafeException() {
        // 실패한 호출도 횟수를 남기고 공급자 원문·URL·키를 외부 예외에 담지 않는다.
        when(provider.call(any())).thenThrow(new IllegalStateException("private provider details"));
        try (var scope = collector.open()) {
            assertThatThrownBy(() -> model.embed("question"))
                    .hasMessage("Embedding request failed").satisfies(error -> assertThat(error.getCause()).isNull());
            assertThat(scope.callCount()).isEqualTo(1);
            assertThat(scope.report().embeddingCalls().getFirst().available()).isFalse();
        }
    }
    @Test void missingNativePromptTokensAreNotReplacedWithZero() {
        // DefaultUsage가 nullable 값을 0으로 바꿔도 native OpenAI 값의 누락을 보존한다.
        when(provider.call(any())).thenReturn(response(null, 4));
        try (var scope = collector.open()) {
            model.embed("question");
            var call = scope.report().embeddingCalls().getFirst();
            assertThat(call.inputTokens()).isNull();
            assertThat(call.totalTokens()).isEqualTo(4L);
            assertThat(call.available()).isFalse();
        }
    }
    @Test void noUsageMetadataDoesNotBecomeReportedZero() {
        // EmptyUsage는 실제 토큰0 보고와 구분한다.
        when(provider.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1,0},0))));
        try (var scope = collector.open()) {
            model.embed("question");
            assertThat(scope.report().embeddingCalls().getFirst().inputTokens()).isNull();
        }
    }
    @Test void closedScopeNeverLeaksUsageIntoNextOperation() {
        // 실행별 ThreadLocal은 close 후 제거돼 다음 질문·색인에 섞이지 않는다.
        when(provider.call(any())).thenReturn(response(2, 2));
        try (var scope = collector.open()) { model.embed("first"); }
        try (var scope = collector.open()) { assertThat(scope.callCount()).isZero(); }
    }
    @Test void embeddingWithoutOperationScopeIsRejectedBeforeProviderCall() {
        // 연결할 기록이 없는 호출을 실행해 과금 사용량을 잃지 않는다.
        assertThatThrownBy(() -> model.embed("question")).hasMessage("Embedding usage scope required");
        verifyNoInteractions(provider);
    }
    @Test void nestedScopeCannotOverwriteOuterOperation() {
        // 중첩 실행이 바깥 수집기를 덮어쓰지 않도록 명시적으로 거절한다.
        try (var scope = collector.open()) {
            assertThatThrownBy(collector::open).isInstanceOf(IllegalStateException.class);
        }
    }
}
