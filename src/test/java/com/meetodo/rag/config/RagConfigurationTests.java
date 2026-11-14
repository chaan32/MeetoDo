package com.meetodo.rag.config;

import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.rag.embedding.MeteredEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagConfigurationTests {
    private ApplicationContextRunner runner(String profiles) {
        return new ApplicationContextRunner().withUserConfiguration(RagProperties.class,
                EmbeddingUsageCollector.class, RagConfiguration.class)
                .withPropertyValues("spring.profiles.active="+profiles, "MEETODO_RAG_ENABLED=true", "OPENAI_API_KEY=not-real");
    }
    @Test void localConfigurationCreatesOnlyInMemoryStoreWithoutEmbeddingCalls() {
        // 로컬 빈 초기화는 키를 사용해 원격 차원 탐색을 하지 않는다.
        runner("local,openai").run(context -> {
            assertThat(context).hasSingleBean(VectorStore.class).hasSingleBean(MeteredEmbeddingModel.class);
            assertThat(context.getBean(VectorStore.class)).isInstanceOf(SimpleVectorStore.class);
            var model = context.getBean(MeteredEmbeddingModel.class);
            assertThat(model.dimensions()).isEqualTo(1536);
            var provider = ReflectionTestUtils.getField(model, "delegate");
            var options = (OpenAiEmbeddingOptions) ReflectionTestUtils.getField(provider, "defaultOptions");
            assertThat(options.getDimensions()).isEqualTo(1536);
            assertThat(options.getModel()).isEqualTo("text-embedding-3-small");
            try (var scope = context.getBean(EmbeddingUsageCollector.class).open()) {
                assertThat(scope.callCount()).isZero();
            }
        });
    }
    @Test void dockerConfigurationCreatesPgStoreWithoutAutomaticSchemaWrites() {
        // Docker 저장소 생성은 Flyway 소유 스키마에 DDL을 실행하지 않는다.
        var jdbc = mock(JdbcTemplate.class);
        runner("docker,openai").withBean(JdbcTemplate.class, () -> jdbc).run(context -> {
            assertThat(context).hasSingleBean(VectorStore.class);
            assertThat(context.getBean(VectorStore.class)).isInstanceOf(PgVectorStore.class);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
        });
    }
    @Test void testProfileNeverCreatesRealEmbeddingClient() {
        // 테스트는 플래그·OpenAI 프로필이 있어도 외부 클라이언트 빈을 만들지 않는다.
        runner("local,openai,test").run(context -> {
            assertThat(context).doesNotHaveBean(VectorStore.class).doesNotHaveBean(MeteredEmbeddingModel.class);
        });
    }
    @Test void defaultOptOutNeverCreatesRealEmbeddingClient() {
        // 기존 OpenAI 실행에서도 RAG 기본값은 비활성이다.
        runner("local,openai").withPropertyValues("MEETODO_RAG_ENABLED=false").run(context -> {
            assertThat(context).doesNotHaveBean(VectorStore.class).doesNotHaveBean(MeteredEmbeddingModel.class);
        });
    }
}
