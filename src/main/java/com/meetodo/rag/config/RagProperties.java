package com.meetodo.rag.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RagProperties {
    private final boolean enabled;
    private final int chunkTokens;
    private final double threshold;
    private final String model;

    public RagProperties(Environment environment) {
        enabled = "true".equalsIgnoreCase(environment.getProperty("MEETODO_RAG_ENABLED", "false").strip());
        chunkTokens = integer(environment, "MEETODO_RAG_CHUNK_TOKENS", 300);
        if (chunkTokens < 1 || chunkTokens > 8000) throw invalid("MEETODO_RAG_CHUNK_TOKENS");
        String value = environment.getProperty("MEETODO_RAG_SIMILARITY_THRESHOLD", "0.75");
        try { threshold = Double.parseDouble(value.isBlank() ? "0.75" : value.strip()); }
        catch (NumberFormatException ignored) { throw invalid("MEETODO_RAG_SIMILARITY_THRESHOLD"); }
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) throw invalid("MEETODO_RAG_SIMILARITY_THRESHOLD");
        String configuredModel = environment.getProperty("MEETODO_EMBEDDING_MODEL", "").strip();
        model = configuredModel.isEmpty() ? "text-embedding-3-small" : configuredModel;
        if (!model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw invalid("MEETODO_EMBEDDING_MODEL");
    }
    public boolean enabled() { return enabled; }
    public int chunkTokens() { return chunkTokens; }
    public double similarityThreshold() { return threshold; }
    public String embeddingModel() { return model; }
    public int dimensions() { return 1536; }
    public int topK() { return 5; }

    private static int integer(Environment environment, String name, int fallback) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.strip()); }
        catch (NumberFormatException ignored) { throw invalid(name); }
    }
    private static IllegalArgumentException invalid(String name) { return new IllegalArgumentException("Invalid setting: " + name); }
}
