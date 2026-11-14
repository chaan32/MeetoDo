package com.meetodo.rag.index;

import com.meetodo.meeting.*;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rag-commit;DB_CLOSE_DELAY=-1", "MEETODO_RAG_ENABLED=true"})
@ActiveProfiles("test")
class RagIndexCommitIntegrationTests {
    @Autowired MeetingCompletionService completion;
    @Autowired MeetingRepository meetings;
    @Autowired RagIndexRunRepository runs;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean com.meetodo.rag.search.RagRetriever questionRetriever;
    @MockitoBean com.meetodo.rag.question.RagAnswerClient questionAnswerClient;
    @MockitoBean VectorStore vectors;
    @MockitoBean(name="ragIndexExecutor") Executor executor;

    @BeforeEach void directExecution() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
    }

    // 실제 트랜잭션 커밋 전에는 색인하지 않고 알림이 비활성이어도 커밋 후 색인한다.
    @Test void indexesOnlyAfterCommitEvenWhenNotifierIsDisabled() {
        Meeting meeting = done("알림 비활성 회의도 검색 색인 대상으로 등록한다.");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            completion.finish(meeting);
            verify(vectors, never()).add(anyList());
        });
        verify(vectors).add(anyList());
        assertThat(meetings.findById(meeting.getId()).orElseThrow().getStatus()).isEqualTo(MeetingStatus.DONE);
    }

    // 색인 저장소 실패는 별도 실패 기록만 남기며 이미 커밋된 회의 DONE을 되돌리지 않는다.
    @Test void indexingFailureAfterCommitPreservesSuccessfulMeeting() {
        doThrow(new IllegalStateException("private provider details")).when(vectors).add(anyList());
        Meeting meeting = done("검색 저장소 실패를 회의 처리 결과와 격리한다.");
        assertThatCode(() -> completion.finish(meeting)).doesNotThrowAnyException();
        verify(vectors).add(anyList());
        assertThat(meetings.findById(meeting.getId()).orElseThrow().getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(runs.findAll()).anySatisfy(run -> {
            assertThat(run.getMeetingId()).isEqualTo(meeting.getId());
            assertThat(run.getStatus().toString()).isEqualTo("FAILED");
            assertThat(run.getErrorCode()).doesNotContain("private", "provider");
        });
    }

    private Meeting done(String text) {
        Meeting meeting = Meeting.create(text, MeetingSource.API_TEXT);
        Instant at = Instant.parse("2026-11-14T00:00:00Z");
        meeting.startProcessing(at);
        meeting.complete("완료", at.plusSeconds(1), 1, 0, 0);
        return meeting;
    }
}
