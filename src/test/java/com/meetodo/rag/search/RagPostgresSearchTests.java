package com.meetodo.rag.search;

import com.meetodo.rag.config.RagProperties;
import com.meetodo.rag.embedding.*;
import com.meetodo.rag.index.RagIndexDocuments;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class RagPostgresSearchTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    private final EmbeddingUsageCollector collector = new EmbeddingUsageCollector();
    private final MeteredEmbeddingModel model = new MeteredEmbeddingModel(new EmbeddingModel() {
        public EmbeddingResponse call(EmbeddingRequest request) {
            return new EmbeddingResponse(request.getInstructions().stream().map(text -> new Embedding(vector(text), 0)).toList());
        }
        public float[] embed(Document document) { return embed(document.getText()); }
        public int dimensions() { return 1536; }
        private float[] vector(String text) {
            float[] value = new float[1536];
            switch (text) {
                case "middle" -> { value[0] = 0.6f; value[1] = 0.8f; }
                case "zero" -> value[1] = 1;
                case "negative" -> { value[0] = -0.6f; value[1] = 0.8f; }
                case "worst" -> value[0] = -1;
                default -> value[0] = 1;
            }
            return value;
        }
    }, collector, 1536);

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
    @BeforeEach void clearVectors() { jdbc.update("DELETE FROM rag_vector_store"); }

    @Test void diagnosticsUsesActualPgCosineWithoutThresholdAndEmbedsExactlyOnce() {
        // 실제 pgvector SQL에서 namespace를 제한하고 음수·0 점수도 상위 다섯 건에 포함한다.
        var retriever = retriever();
        try (var scope = collector.open()) {
            var hits = retriever.retrieve("question", true);
            assertThat(hits).extracting(Document::getText).containsExactly("best", "middle", "zero", "negative", "worst");
            assertThat(hits.get(2).getScore()).isZero();
            assertThat(hits.get(3).getScore()).isNegative();
            assertThat(scope.callCount()).isEqualTo(1);
        }
    }
    @Test void normalPgSearchUsesThresholdAndEmbedsExactlyOnce() {
        // 일반 검색은 동일 PostgreSQL 색인에서 임계값을 적용하고 임베딩 한 번만 집계한다.
        var retriever = retriever();
        try (var scope = collector.open()) {
            assertThat(retriever.retrieve("question", false)).extracting(Document::getText).containsExactly("best");
            assertThat(scope.callCount()).isEqualTo(1);
        }
    }
    @Test void diagnosticsFindsExactTopFiveWhenOtherNamespacesFillHnswCandidates() {
        // HNSW 후보 40개를 다른 namespace가 채워도 현재 namespace의 음수 점수 상위 5개를 반환한다.
        var retriever = retriever();
        jdbc.update("DELETE FROM rag_vector_store");
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < 256; i++) rows.add(vectorRow("foreign-" + i, "foreign", 0.80f + i * 0.0007f));
        for (int i = 1; i <= 6; i++) rows.add(vectorRow("current-" + i, "active", -i / 10.0f));
        jdbc.batchUpdate("INSERT INTO rag_vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)", rows);
        jdbc.execute("ANALYZE rag_vector_store");

        var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            jdbc.execute("SET LOCAL hnsw.ef_search = 40");
            String queryVector = vectorLiteral(1);
            var plan = jdbc.queryForList("EXPLAIN SELECT id FROM rag_vector_store WHERE metadata->>'namespace' = ? ORDER BY embedding <=> ?::vector LIMIT 5",
                    String.class, "active", queryVector);
            assertThat(String.join("\n", plan)).contains("Index Scan", "idx_rag_vector_cosine");
            try (var scope = collector.open()) {
                var hits = retriever.retrieve("question", true);
                assertThat(hits).extracting(Document::getText)
                        .containsExactly("current-1", "current-2", "current-3", "current-4", "current-5");
                assertThat(hits).allSatisfy(hit -> assertThat(hit.getScore()).isNegative());
                assertThat(scope.callCount()).isEqualTo(1);
            }
        });
    }
    private Object[] vectorRow(String text, String namespace, float cosine) {
        return new Object[]{UUID.randomUUID().toString(), text, "{\"namespace\":\"" + namespace + "\"}", vectorLiteral(cosine)};
    }
    private String vectorLiteral(float cosine) {
        float[] vector = new float[1536];
        vector[0] = cosine;
        vector[1] = (float) Math.sqrt(1 - cosine * cosine);
        return Arrays.toString(vector);
    }
    private RagRetriever retriever() {
        var properties = new RagProperties(new MockEnvironment());
        var documents = mock(RagIndexDocuments.class);
        when(documents.namespace()).thenReturn("active");
        var store = PgVectorStore.builder(jdbc, model).vectorTableName("rag_vector_store")
                .dimensions(1536).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).initializeSchema(false).build();
        store.afterPropertiesSet();
        try (var scope = collector.open()) {
            store.add(List.of("best", "middle", "zero", "negative", "worst", "excluded").stream().map(text ->
                    Document.builder().id(UUID.randomUUID().toString()).text(text)
                            .metadata(Map.of("namespace", text.equals("excluded") ? "foreign" : "active")).build()).toList());
        }
        return new RagRetriever(store, model, properties, documents);
    }
}
