package com.meetodo.rag.index;

import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.usage.UsagePricing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagIndexerTests {
    RagIndexDocuments documents = mock(RagIndexDocuments.class);
    RagIndexRunRepository runs = mock(RagIndexRunRepository.class);
    VectorStore vectors = mock(VectorStore.class);
    Clock clock = Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    RagIndexer indexer;
    @BeforeEach void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(documents.namespace()).thenReturn("test:1536:300:v1");
        when(documents.forMeeting(1L)).thenReturn(List.of(new Document("b2088b4e-31b1-4d18-ad45-a0afce983567", "회의 근거", Map.of("meetingId", 1L))));
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        indexer = new RagIndexer(documents, runs, vectors, new EmbeddingUsageCollector(),
                new UsagePricing(new MockEnvironment()), manager, clock);
    }
    @Test void successfulIndexStoresRunAndDocumentIds() {
        // 성공한 색인은 실행 상태·문서 ID와 종료 시각을 별도 이력으로 남긴다.
        var run = indexer.indexMeeting(1L);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RagIndexRun.Status.INDEXED);
        assertThat(run.getDocumentIds()).contains("b2088b4e-31b1-4d18-ad45-a0afce983567");
        assertThat(run.getFinishedAt()).isEqualTo(clock.instant());
        verify(vectors).add(anyList());
    }
    @Test void embeddingFailureIsRecordedWithoutLeakingProviderText() {
        // 공급자 예외가 나도 색인은 FAILED로 기록하고 원문 오류를 노출하지 않는다.
        doThrow(new IllegalStateException("private-provider-body")).when(vectors).add(anyList());
        var run = indexer.indexMeeting(1L);
        assertThat(run).isNotNull();
        assertThat(run.getStatus()).isEqualTo(RagIndexRun.Status.FAILED);
        assertThat(run.getErrorCode()).isEqualTo("INDEX_FAILED");
        assertThat(run.getErrorCode()).doesNotContain("private");
    }
    @Test void missingEligibleDocumentsDoNotCallEmbeddingOrCreateRun() {
        // 완료되지 않았거나 없는 회의는 외부 호출과 실행 기록을 만들지 않는다.
        when(documents.forMeeting(1L)).thenReturn(List.of());
        assertThat(indexer.indexMeeting(1L)).isNull();
        verifyNoInteractions(vectors, runs);
    }
    @Test void reindexDeletesObsoletePreviousDocumentIdsAfterAddingCurrentDocuments() {
        // 청크 수가 줄면 이전 색인에만 남은 문서 ID를 정리한다.
        var old = new RagIndexRun(1L, "test:1536:300:v1", clock.instant());
        org.springframework.test.util.ReflectionTestUtils.setField(old, "documentIds", "[\"old-doc\"]");
        when(runs.findAllByMeetingId(1L)).thenReturn(List.of(old));
        indexer.indexMeeting(1L);
        verify(vectors).delete(List.of("old-doc"));
    }
    @Test void latestFailedIndexCannotBeReportedAsReady() {
        // 일부 회의 색인이 실패하면 검색 준비 완료로 보고하지 않는다.
        var run = indexer.indexMeeting(1L);
        assertThat(run).isNotNull();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "status", RagIndexRun.Status.FAILED);
        when(documents.eligibleMeetingIds()).thenReturn(List.of(1L));
        when(runs.findAllByOrderByIdDesc()).thenReturn(List.of(run));
        assertThat(indexer.status().ready()).isFalse();
        assertThat(indexer.status().failed()).isEqualTo(1);
    }
    @Test void differentModelNamespaceCannotBeReportedAsReady() {
        // 모델 공간이 다른 과거 색인을 현재 모델의 검색 준비 상태로 쓰지 않는다.
        var old = new RagIndexRun(1L, "different-model:1536:300:v1", clock.instant());
        org.springframework.test.util.ReflectionTestUtils.setField(old, "status", RagIndexRun.Status.INDEXED);
        when(documents.eligibleMeetingIds()).thenReturn(List.of(1L));
        when(runs.findAllByOrderByIdDesc()).thenReturn(List.of(old));
        assertThat(indexer.status().pending()).isEqualTo(1);
        assertThat(indexer.status().ready()).isFalse();
    }
    @Test void emptyLocalMemoryCannotReusePersistedIndexedStatusAfterRestart() {
        // local 프로세스 재시작 뒤 메모리 색인이 비었으면 DB의 과거 성공 기록만으로 준비 완료라 하지 않는다.
        var old = new RagIndexRun(1L, "test:1536:300:v1", clock.instant());
        org.springframework.test.util.ReflectionTestUtils.setField(old, "status", RagIndexRun.Status.INDEXED);
        when(documents.eligibleMeetingIds()).thenReturn(List.of(1L));
        when(runs.findAllByOrderByIdDesc()).thenReturn(List.of(old));
        var memory = org.springframework.ai.vectorstore.SimpleVectorStore.builder(mock(org.springframework.ai.embedding.EmbeddingModel.class)).build();
        var manager = mock(PlatformTransactionManager.class);
        var local = new RagIndexer(documents, runs, memory, new EmbeddingUsageCollector(),
                new UsagePricing(new MockEnvironment()), manager, clock);
        assertThat(local.status().ready()).isFalse();
        assertThat(local.status().pending()).isEqualTo(1);
    }

    @Test void listenerIsolatesIndexFailureFromCommittedMeeting() {
        // 커밋 후 색인 오류는 회의 처리 호출 경계로 전파하지 않는다.
        var failing = mock(RagIndexer.class);
        doThrow(new IllegalStateException("private-body")).when(failing).indexMeeting(1L);
        var listener = new RagIndexListener(failing, documents, Runnable::run);
        assertThatCode(() -> listener.completed(new MeetingProcessedEvent(1L))).doesNotThrowAnyException();
        verify(failing).indexMeeting(1L);
    }
    @Test void rejectedQueueDoesNotFailCompletedMeeting() {
        // 큐가 가득 차도 회의 DONE 결과를 실패시키지 않는다.
        var listener = new RagIndexListener(indexer, documents, task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThatCode(() -> listener.completed(new MeetingProcessedEvent(1L))).doesNotThrowAnyException();
    }
}
