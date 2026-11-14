package com.meetodo.rag.search;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

public class RagMemoryVectorStore extends SimpleVectorStore {
    public RagMemoryVectorStore(EmbeddingModel model) { super(SimpleVectorStore.builder(model)); }

    public List<Document> unfilteredSearch(String query, String namespace, int topK) {
        var request = SearchRequest.builder().query(query).topK(topK)
                .filterExpression(new FilterExpressionBuilder().eq("namespace", namespace).build()).build();
        // Spring AI 1.1.8's accept-all threshold is 0, which still excludes negative cosine scores.
        return super.doSimilaritySearch(new SearchRequest(request) {
            @Override public double getSimilarityThreshold() { return Double.NEGATIVE_INFINITY; }
        });
    }
}
