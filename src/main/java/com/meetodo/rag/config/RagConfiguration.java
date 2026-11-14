package com.meetodo.rag.config;

import com.meetodo.rag.embedding.EmbeddingUsageCollector;
import com.meetodo.rag.embedding.MeteredEmbeddingModel;
import java.time.Duration;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile("(local | docker) & openai & !test")
@Conditional(RagEnabled.class)
public class RagConfiguration {
    @Bean MeteredEmbeddingModel ragEmbeddingModel(Environment environment, RagProperties properties,
                                                  EmbeddingUsageCollector collector) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(30));
        var api = OpenAiApi.builder().apiKey(environment.getProperty("OPENAI_API_KEY"))
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var options = OpenAiEmbeddingOptions.builder().model(properties.embeddingModel()).dimensions(properties.dimensions()).build();
        var provider = new OpenAiEmbeddingModel(api, MetadataMode.NONE, options,
                RetryTemplate.builder().maxAttempts(1).build());
        return new MeteredEmbeddingModel(provider, collector, properties.dimensions());
    }

    @Bean @Profile("local & !docker")
    VectorStore localRagVectorStore(MeteredEmbeddingModel model) {
        return SimpleVectorStore.builder(model).build();
    }

    @Bean @Profile("docker") @DependsOnDatabaseInitialization
    VectorStore postgresRagVectorStore(JdbcTemplate jdbc, MeteredEmbeddingModel model, RagProperties properties) {
        return PgVectorStore.builder(jdbc, model).vectorTableName("rag_vector_store")
                .dimensions(properties.dimensions()).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW).initializeSchema(false).build();
    }
}
