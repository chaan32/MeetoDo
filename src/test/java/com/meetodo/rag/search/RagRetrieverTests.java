package com.meetodo.rag.search;

import com.meetodo.rag.config.RagProperties;
import com.meetodo.rag.embedding.*;
import com.meetodo.rag.index.RagIndexDocuments;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagRetrieverTests {
    private final RagProperties properties = new RagProperties(new MockEnvironment());
    private final RagIndexDocuments documents = mock(RagIndexDocuments.class);
    private final EmbeddingUsageCollector collector = new EmbeddingUsageCollector();
    private final MeteredEmbeddingModel model = new MeteredEmbeddingModel(new EmbeddingModel() {
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(request.getInstructions().stream().map(text ->
                    new Embedding(vector(text), 0)).toList());
        }
        public float[] embed(Document document) { return embed(document.getText()); }
        private float[] vector(String text) {
            return switch (text) {
                case "zero" -> new float[]{0, 1};
                case "negative" -> new float[]{-0.6f, 0.8f};
                case "worst" -> new float[]{-1, 0};
                case "middle" -> new float[]{0.6f, 0.8f};
                default -> new float[]{1, 0};
            };
        }
    }, collector, 2);

    @Test void normalSearchKeepsScoreStrictlyAboveThreshold() {
        // 경계보다 높은 결과만 답변 근거로 남긴다.
        assertNormalScore(0.75001, true);
    }
    @Test void normalSearchDropsScoreEqualToThreshold() {
        // SimpleVectorStore의 >= 정책을 서버의 엄격한 > 정책으로 통일한다.
        assertNormalScore(0.75, false);
    }
    @Test void normalSearchDropsScoreBelowThreshold() {
        // 저장소 응답을 신뢰하지 않고 서버에서 임계값을 다시 검사한다.
        assertNormalScore(0.74999, false);
    }
    @Test void normalSearchRejectsNonFiniteScore() {
        // NaN 점수는 응답으로 전파하지 않고 일반화한 검색 오류로 처리한다.
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit("bad", Double.NaN)));
        assertThatThrownBy(() -> retriever(store).retrieve("private question", false))
                .isInstanceOf(IllegalStateException.class).hasMessage("RAG search failed");
    }
    @Test void normalSearchRejectsMissingScore() {
        // 점수가 빠진 저장소 결과를 근거로 사용하지 않는다.
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("bad").text("bad").metadata(Map.of("namespace", "active")).build()));
        assertThatThrownBy(() -> retriever(store).retrieve("question", false))
                .hasMessage("RAG search failed").hasNoCause();
    }
    @Test void normalSearchDropsUnexpectedNamespace() {
        // 저장소가 다른 namespace 문서를 반환해도 서버 응답에는 포함하지 않는다.
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("foreign").text("best").metadata(Map.of("namespace", "foreign")).score(1.0).build()));
        assertThat(retriever(store).retrieve("question", false)).isEmpty();
    }
    @Test void normalSearchUsesCurrentNamespaceAndTopFive() {
        // 다른 임베딩 모델·청크 설정의 문서를 검색 대상에 섞지 않는다.
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            assertThat(request.getTopK()).isEqualTo(5);
            assertThat(request.getSimilarityThreshold()).isEqualTo(0.75);
            assertThat(request.getFilterExpression().toString()).contains("namespace", "active");
            return List.of();
        });
        assertThat(retriever(store).retrieve("question", false)).isEmpty();
    }
    @Test void diagnosticsReturnsTrueTopFiveIncludingZeroAndNegativeCosine() {
        // 0 임계값도 누락하는 음수 유사도까지 진단 상위 다섯 건으로 돌려준다.
        var store = populatedStore();
        try (var scope = collector.open()) {
            var hits = retriever(store).retrieve("query", true);
            assertThat(hits).extracting(Document::getText).containsExactly("best", "middle", "zero", "negative", "worst");
            assertThat(hits.get(2).getScore()).isZero();
            assertThat(hits.get(3).getScore()).isNegative();
            assertThat(scope.callCount()).isEqualTo(1);
        }
    }
    @Test void normalMemorySearchEmbedsExactlyOnce() {
        // 일반 검색도 과금 집계 스코프 안에서 질문을 한 번만 임베딩한다.
        var store = populatedStore();
        try (var scope = collector.open()) {
            assertThat(retriever(store).retrieve("query", false)).extracting(Document::getText).containsExactly("best");
            assertThat(scope.callCount()).isEqualTo(1);
        }
    }
    @Test void deletedDocumentsDoNotRemainInDiagnostics() {
        // 재색인에서 삭제한 문서는 진단 캐시에도 남아 있지 않아야 한다.
        var store = populatedStore();
        store.delete(List.of("best"));
        try (var scope = collector.open()) {
            assertThat(retriever(store).retrieve("query", true)).extracting(Document::getId).doesNotContain("best");
        }
    }
    @Test void providerErrorsDoNotExposeQuestionOrProviderDetails() {
        // 공급자 본문과 URL은 검색 오류 메시지나 원인으로 노출하지 않는다.
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenThrow(new IllegalStateException("secret https://provider/private"));
        assertThatThrownBy(() -> retriever(store).retrieve("private question", false))
                .hasMessage("RAG search failed").hasNoCause();
    }
    private void assertNormalScore(double score, boolean accepted) {
        var store = mock(VectorStore.class);
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit("one", score)));
        assertThat(retriever(store).retrieve("question", false)).hasSize(accepted ? 1 : 0);
    }
    private RagRetriever retriever(VectorStore store) {
        when(documents.namespace()).thenReturn("active");
        return new RagRetriever(store, model, properties, documents);
    }
    private Document hit(String id, double score) {
        return Document.builder().id(id).text(id).metadata(Map.of("namespace", "active")).score(score).build();
    }
    private RagMemoryVectorStore populatedStore() {
        var store = new RagMemoryVectorStore(model);
        try (var scope = collector.open()) {
            store.add(List.of("best", "middle", "zero", "negative", "worst").stream()
                    .map(text -> Document.builder().id(text).text(text).metadata(Map.of("namespace", "active")).build()).toList());
            store.add(List.of(Document.builder().id("foreign").text("best").metadata(Map.of("namespace", "foreign")).build()));
        }
        return store;
    }
}
