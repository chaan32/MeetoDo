package com.meetodo.rag.embedding;

import com.meetodo.usage.UsageCall;
import com.meetodo.usage.UsageReport;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingUsageCollector {
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    public Scope open() {
        if (current.get() != null) throw new IllegalStateException("Embedding usage scope already active");
        Scope scope = new Scope();
        current.set(scope);
        return scope;
    }
    int nextOrder() { return active().calls.size() + 1; }
    void record(UsageCall call) { active().calls.add(call); }
    private Scope active() {
        Scope scope = current.get();
        if (scope == null) throw new IllegalStateException("Embedding usage scope required");
        return scope;
    }
    public final class Scope implements AutoCloseable {
        private final List<UsageCall> calls = new ArrayList<>();
        public UsageReport report() { return new UsageReport(List.of(), null, calls); }
        public int callCount() { return calls.size(); }
        @Override public void close() { if (current.get() == this) current.remove(); }
    }
}
