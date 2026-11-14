package com.meetodo.persistence;

import org.flywaydb.core.Flyway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RagVectorMigrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test void flywayCreatesVectorExtensionWithoutVectorStoreSchemaInitialization() {
        // 벡터 저장소 빈을 만들지 않아도 Flyway가 확장을 설치한다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname='vector'", Integer.class)).isEqualTo(1);
    }

    @Test void flywayCreatesSeparateAppendOnlyIndexRunTable() {
        // 색인 사용량과 실패 상태는 회의의 기존 사용량을 덮지 않는 별도 테이블에 저장한다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='rag_index_runs'", Integer.class)).isEqualTo(1);
    }

    @Test void vectorSchemaUsesExplicitDimensionsAndCosineHnswIndex() {
        // 실제 벡터 컬럼 차원과 연산자 클래스를 확인하며 Hibernate validate만 믿지 않는다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='rag_vector_store'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT format_type(atttypid,atttypmod) FROM pg_attribute WHERE attrelid='public.rag_vector_store'::regclass AND attname='embedding'", String.class)).contains("vector(1536)");
        assertThat(jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='rag_vector_store'", String.class))
                .anySatisfy(definition -> assertThat(definition).contains("hnsw", "vector_cosine_ops"));
    }

    @Test void upgradeFromV13PreservesExistingMeetingsTasksAndUsage() {
        // V13의 기존 본문·할 일·사용량을 보존하면서 RAG 스키마만 추가한다.
        String schema = "rag_v13_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).target("13").load().migrate();
        jdbc.update("INSERT INTO rag_v13_upgrade.meetings(id,raw_text,content_hash,source,status,llm_call_count,tool_call_count,validation_failure_count,created_at,usage_report) VALUES (7,'보존할 회의','rag-upgrade-hash','API_TEXT','DONE',4,3,0,now(),'{\"chatCalls\":[]}')");
        jdbc.update("INSERT INTO rag_v13_upgrade.tasks(id,title,status,meeting_id,created_at) VALUES (8,'보존할 할 일','TODO',7,now())");
        var upgrade = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).schemas(schema).target("15").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("SELECT raw_text,llm_call_count,usage_report FROM rag_v13_upgrade.meetings WHERE id=7"))
                .containsEntry("raw_text", "보존할 회의").containsEntry("llm_call_count", 4).containsEntry("usage_report", "{\"chatCalls\":[]}");
        assertThat(jdbc.queryForMap("SELECT title,status,meeting_id FROM rag_v13_upgrade.tasks WHERE id=8"))
                .containsEntry("title", "보존할 할 일").containsEntry("status", "TODO").containsEntry("meeting_id", 7L);
    }

    @Test void springVectorStorePersistsAndFindsDocumentsWithMockedEmbeddings() {
        // 실제 PostgreSQL 코사인 검색과 문서 메타데이터를 고정 벡터로 검증하고 외부 임베딩은 호출하지 않는다.
        assertThat(vectorTableExists()).isTrue();
        var calls = new AtomicInteger();
        var store = vectorStore(calls);
        store.add(List.of(
                Document.builder().id("00000000-0000-0000-0000-000000000071").text("결제 API 스펙 정리")
                        .metadata(Map.of("meetingId", 71, "kind", "TASK")).build(),
                Document.builder().id("00000000-0000-0000-0000-000000000072").text("배포 체크리스트 검토")
                        .metadata(Map.of("meetingId", 71, "kind", "TASK")).build()));
        var hits = store.similaritySearch(SearchRequest.builder().query("결제 업무")
                .topK(5).similarityThreshold(0.5).filterExpression("meetingId == 71").build());
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().getId()).isEqualTo("00000000-0000-0000-0000-000000000071");
        assertThat(hits.getFirst().getMetadata()).containsEntry("meetingId", 71).containsEntry("kind", "TASK");
        assertThat(hits.getFirst().getScore()).isCloseTo(1.0, within(0.000001));
        assertThat(calls).hasValue(2);
    }

    @Test void reindexingStableDocumentIdUpdatesInsteadOfDuplicatingRows() {
        // 같은 UUID 재색인은 기존 벡터 행을 갱신하므로 문서가 계속 늘어나지 않는다.
        assertThat(vectorTableExists()).isTrue();
        var store = vectorStore(new AtomicInteger());
        String id = "00000000-0000-0000-0000-000000000081";
        store.add(List.of(Document.builder().id(id).text("결제 명세 초안").metadata(Map.of("meetingId", 81)).build()));
        store.add(List.of(Document.builder().id(id).text("결제 명세 최신본").metadata(Map.of("meetingId", 81)).build()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_vector_store WHERE id=?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM rag_vector_store WHERE id=?::uuid", String.class, id)).isEqualTo("결제 명세 최신본");
    }

    private boolean vectorTableExists() {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='rag_vector_store'", Integer.class) == 1;
    }

    private PgVectorStore vectorStore(AtomicInteger calls) {
        var model = new EmbeddingModel() {
            @Override public EmbeddingResponse call(EmbeddingRequest request) {
                calls.incrementAndGet();
                var vectors = new ArrayList<Embedding>();
                for (String text : request.getInstructions()) vectors.add(new Embedding(vector(text), vectors.size()));
                return new EmbeddingResponse(vectors);
            }
            @Override public float[] embed(Document document) { return vector(document.getText()); }
            @Override public int dimensions() { return 1536; }
            private float[] vector(String text) {
                float[] vector = new float[1536];
                vector[text.contains("결제") ? 0 : 1] = 1;
                return vector;
            }
        };
        var store = PgVectorStore.builder(jdbc, model).vectorTableName("rag_vector_store")
                .dimensions(1536).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(false).build();
        store.afterPropertiesSet();
        assertThat(calls).hasValue(0);
        return store;
    }

}
