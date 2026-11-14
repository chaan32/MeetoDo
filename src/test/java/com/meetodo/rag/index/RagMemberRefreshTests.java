package com.meetodo.rag.index;

import com.meetodo.member.MemberChangedEvent;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:rag-member-refresh;DB_CLOSE_DELAY=-1", "MEETODO_RAG_ENABLED=true"})
@ActiveProfiles("test")
class RagMemberRefreshTests {
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean RagIndexer indexer;
    @MockitoBean VectorStore vectors;
    @MockitoBean(name = "ragIndexExecutor") Executor executor;

    @BeforeEach void directExecution() {
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executor).execute(any());
    }
    @Test void committedMemberChangeRefreshesIndexedOwnerNamesAndTeams() {
        // 팀원 표시 정보가 바뀌면 커밋 후 TASK 검색 문서도 갱신하며 이름 검색 알고리즘은 바꾸지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            events.publishEvent(new MemberChangedEvent());
            verify(indexer, never()).reindexAll();
        });
        verify(indexer).reindexAll();
    }
    @Test void refreshFailureDoesNotEscapeAfterCommitEventDispatch() {
        // 커밋 후 리스너의 색인 오류를 이벤트 발행 경계로 전파하지 않는다.
        when(indexer.reindexAll()).thenThrow(new IllegalStateException("private-provider-body"));
        assertThatCode(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                events.publishEvent(new MemberChangedEvent()))).doesNotThrowAnyException();
        verify(indexer).reindexAll();
    }
}
