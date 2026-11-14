package com.meetodo.rag.index;

import com.meetodo.rag.config.RagEnabled;
import com.meetodo.task.*;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Conditional(RagEnabled.class)
public class RagIndexListener {
    private static final Logger log = LoggerFactory.getLogger(RagIndexListener.class);
    private final RagIndexer indexer;
    private final RagIndexDocuments documents;
    private final Executor executor;

    public RagIndexListener(RagIndexer indexer, RagIndexDocuments documents, @Qualifier("ragIndexExecutor") Executor executor) {
        this.indexer = indexer; this.documents = documents; this.executor = executor;
    }
    @TransactionalEventListener
    public void completed(MeetingProcessedEvent event) { enqueue(() -> indexer.indexMeeting(event.meetingId())); }
    @TransactionalEventListener
    public void created(TaskCreatedEvent event) { task(event.taskId()); }
    @TransactionalEventListener
    public void changed(TaskChangedEvent event) { task(event.taskId()); }
    @TransactionalEventListener
    public void assigned(TaskAssignedEvent event) { task(event.taskId()); }
    @TransactionalEventListener
    public void reassigned(TaskReassignedEvent event) { task(event.taskId()); }
    @TransactionalEventListener
    public void memberChanged(com.meetodo.member.MemberChangedEvent event) { enqueue(indexer::reindexAll); }
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() { enqueue(indexer::bootstrap); }

    private void task(Long id) { enqueue(() -> documents.meetingIdsForTask(id).forEach(indexer::indexMeeting)); }
    private void enqueue(Runnable action) {
        try {
            executor.execute(() -> {
                try { action.run(); }
                catch (RuntimeException ignored) { log.warn("RAG index execution failed: INDEX_FAILED"); }
            });
        } catch (RuntimeException ignored) {
            log.warn("RAG index scheduling failed: INDEX_QUEUE_REJECTED");
        }
    }
}
