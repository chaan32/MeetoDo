package com.meetodo.rag.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetodo.rag.config.*;
import com.meetodo.rag.embedding.MeteredEmbeddingModel;
import com.meetodo.rag.index.RagIndexDocuments;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Conditional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Conditional(RagEnabled.class)
public class RagRetriever {
    private final VectorStore store;
    private final MeteredEmbeddingModel model;
    private final RagProperties properties;
    private final RagIndexDocuments documents;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagRetriever(VectorStore store, MeteredEmbeddingModel model, RagProperties properties, RagIndexDocuments documents) {
        this.store = store; this.model = model; this.properties = properties; this.documents = documents;
    }

    public List<Document> retrieve(String question, boolean ignoreThreshold) {
        try {
            String namespace = documents.namespace();
            List<Document> hits;
            if (!ignoreThreshold) {
                hits = store.similaritySearch(SearchRequest.builder().query(question).topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .filterExpression(new FilterExpressionBuilder().eq("namespace", namespace).build()).build());
            } else if (store instanceof RagMemoryVectorStore memory) {
                hits = memory.unfilteredSearch(question, namespace, properties.topK());
            } else if (store instanceof PgVectorStore postgres) {
                hits = postgresSearch(postgres, question, namespace);
            } else {
                throw new IllegalStateException();
            }
            for (Document hit : hits) {
                if (hit == null || hit.getScore() == null || !Double.isFinite(hit.getScore())) throw new IllegalStateException();
            }
            return hits.stream().filter(hit -> namespace.equals(hit.getMetadata().get("namespace")))
                    .filter(hit -> ignoreThreshold || hit.getScore() > properties.similarityThreshold())
                    .limit(properties.topK()).toList();
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("RAG search failed");
        }
    }

    private List<Document> postgresSearch(PgVectorStore postgres, String question, String namespace) {
        float[] embedding = model.embed(question);
        if (embedding.length != properties.dimensions()) throw new IllegalStateException();
        var vector = new StringBuilder("[");
        double norm = 0;
        for (int i = 0; i < embedding.length; i++) {
            if (!Float.isFinite(embedding[i])) throw new IllegalStateException();
            if (i > 0) vector.append(',');
            vector.append(embedding[i]);
            norm += (double) embedding[i] * embedding[i];
        }
        if (norm == 0) throw new IllegalStateException();
        vector.append(']');
        JdbcTemplate jdbc = postgres.<JdbcTemplate>getNativeClient().orElseThrow();
        // Materialize the namespace first: HNSW post-filtering can omit its true top K.
        return jdbc.query("""
                WITH namespace_documents AS MATERIALIZED (
                    SELECT id, content, metadata, embedding FROM rag_vector_store
                    WHERE metadata->>'namespace' = ?
                )
                SELECT id, content, metadata, 1 - (embedding <=> ?::vector) AS score
                FROM namespace_documents ORDER BY embedding <=> ?::vector LIMIT ?
                """, (rs, row) -> {
            Map<String, Object> metadata;
            try { metadata = mapper.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}); }
            catch (Exception ignored) { throw new IllegalStateException("Invalid RAG metadata"); }
            return Document.builder().id(rs.getString("id")).text(rs.getString("content"))
                    .metadata(metadata).score(rs.getDouble("score")).build();
        }, namespace, vector.toString(), vector.toString(), properties.topK());
    }
}
