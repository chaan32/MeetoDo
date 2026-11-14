package com.meetodo.rag.embedding;

import com.meetodo.usage.UsageCall;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.openai.api.OpenAiApi;

public class MeteredEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final EmbeddingUsageCollector collector;
    private final int dimensions;

    public MeteredEmbeddingModel(EmbeddingModel delegate, EmbeddingUsageCollector collector, int dimensions) {
        if (dimensions < 1) throw new IllegalArgumentException("Invalid embedding dimensions");
        this.delegate = delegate; this.collector = collector; this.dimensions = dimensions;
    }
    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        int order = collector.nextOrder();
        EmbeddingResponse response;
        try { response = delegate.call(request); }
        catch (RuntimeException ignored) {
            collector.record(UsageCall.raw(order, null, null, null, null, null, "unavailable"));
            throw new IllegalStateException("Embedding request failed");
        }
        collector.record(usage(order, response));
        if (response == null || response.getResults() == null || response.getResults().size() != request.getInstructions().size()
                || response.getResults().stream().anyMatch(result -> result.getOutput() == null || result.getOutput().length != dimensions))
            throw new IllegalStateException("Invalid embedding response");
        return response;
    }
    @Override public float[] embed(Document document) { return embed(getEmbeddingContent(document)); }
    @Override public int dimensions() { return dimensions; }

    private UsageCall usage(int order, EmbeddingResponse response) {
        var metadata = response == null ? null : response.getMetadata();
        String model = metadata == null ? null : metadata.getModel();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null || usage instanceof EmptyUsage)
            return UsageCall.raw(order, model, null, null, null, null, "unavailable");
        Long input; Long total;
        // DefaultUsage replaces missing counts with zero; native counts preserve the provider's actual omissions.
        if (usage.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
            input = count(nativeUsage.promptTokens()); total = count(nativeUsage.totalTokens());
        } else {
            input = count(usage.getPromptTokens()); total = count(usage.getTotalTokens());
        }
        return UsageCall.raw(order, model, input, null, total, null, "embedding");
    }
    private static Long count(Integer value) { return value == null ? null : value.longValue(); }
}
