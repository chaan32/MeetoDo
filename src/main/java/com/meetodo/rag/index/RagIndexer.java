package com.meetodo.rag.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.config.RagEnabled;
import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.usage.UsagePricing;
import com.meetodo.usage.UsageReport;
import java.time.Clock;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Conditional(RagEnabled.class)
public class RagIndexer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RagIndexDocuments documents;
    private final RagIndexRunRepository runs;
    private final VectorStore vectors;
    private final EmbeddingUsageCollector usage;
    private final UsagePricing pricing;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean memoryStore;
    private volatile boolean memoryReady;

    public RagIndexer(RagIndexDocuments documents, RagIndexRunRepository runs, VectorStore vectors,
            EmbeddingUsageCollector usage, UsagePricing pricing, PlatformTransactionManager manager, Clock clock) {
        this.documents = documents; this.runs = runs; this.vectors = vectors; this.usage = usage;
        this.pricing = pricing; this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        memoryStore = vectors instanceof SimpleVectorStore;
        memoryReady = !memoryStore;
    }

    public synchronized RagIndexRun indexMeeting(Long id) {
        List<Document> current = documents.forMeeting(id);
        if (current.isEmpty()) return null;
        List<RagIndexRun> previous = transactions.execute(status -> runs.findAllByMeetingId(id));
        RagIndexRun run = transactions.execute(status -> runs.saveAndFlush(new RagIndexRun(id, documents.namespace(), clock.instant())));
        List<String> ids = current.stream().map(Document::getId).toList();
        try (var capture = usage.open()) {
            try {
                // The embedding HTTP work happens outside the short persistence transactions.
                vectors.add(current);
                Set<String> obsolete = new LinkedHashSet<>();
                previous.forEach(old -> obsolete.addAll(readIds(old.getDocumentIds())));
                obsolete.removeAll(ids);
                if (!obsolete.isEmpty()) vectors.delete(List.copyOf(obsolete));
                finish(run, RagIndexRun.Status.INDEXED, ids, capture.report(), null);
            } catch (RuntimeException failure) {
                finish(run, RagIndexRun.Status.FAILED, ids, capture.report(), "INDEX_FAILED");
            }
        }
        return run;
    }

    private void finish(RagIndexRun run, RagIndexRun.Status status, List<String> ids, UsageReport report, String error) {
        run.finish(status, writeIds(ids), pricing.price(report), error, clock.instant());
        transactions.executeWithoutResult(transaction -> runs.saveAndFlush(run));
    }

    public synchronized List<RagIndexRun> reindexAll() {
        if (memoryStore) memoryReady = false;
        List<RagIndexRun> indexed = new ArrayList<>();
        for (Long id : documents.eligibleMeetingIds()) {
            RagIndexRun run = indexMeeting(id);
            if (run != null) indexed.add(run);
        }
        if (memoryStore) memoryReady = indexed.stream().allMatch(run -> run.getStatus() == RagIndexRun.Status.INDEXED);
        return List.copyOf(indexed);
    }

    public void bootstrap() {
        if (memoryStore) reindexAll();
    }

    public RagIndexStatus status() {
        List<Long> eligible = documents.eligibleMeetingIds();
        Map<Long, RagIndexRun> latest = new HashMap<>();
        List<RagIndexRun> history = transactions.execute(transaction -> runs.findAllByOrderByIdDesc());
        history.forEach(run -> latest.putIfAbsent(run.getMeetingId(), run));
        int indexed = 0, failed = 0;
        for (Long id : eligible) {
            RagIndexRun run = latest.get(id);
            if (run == null || !run.getNamespace().equals(documents.namespace())) continue;
            if (run.getStatus() == RagIndexRun.Status.FAILED) failed++;
            if (run.getStatus() == RagIndexRun.Status.INDEXED && memoryReady) indexed++;
        }
        return new RagIndexStatus(eligible.size(), indexed, failed, eligible.size() - indexed - failed, documents.namespace());
    }

    private static String writeIds(List<String> ids) {
        try { return JSON.writeValueAsString(ids); }
        catch (JsonProcessingException ignored) { throw new IllegalStateException("Cannot encode index identifiers"); }
    }
    private static List<String> readIds(String json) {
        try { return JSON.readValue(json, new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException ignored) { throw new IllegalStateException("Cannot decode index identifiers"); }
    }
}
