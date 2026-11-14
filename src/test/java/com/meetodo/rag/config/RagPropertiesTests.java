package com.meetodo.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RagPropertiesTests {
    @Test void defaultsAreOptInWithTemporaryThresholdAndThreeHundredTokens() {
        // RAG는 기본 비활성이고 임계값은 실측 전 임시값이며 청크 목표는300이다.
        var properties = new RagProperties(new MockEnvironment());
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.chunkTokens()).isEqualTo(300);
        assertThat(properties.similarityThreshold()).isEqualTo(0.75);
        assertThat(properties.dimensions()).isEqualTo(1536);
        assertThat(properties.topK()).isEqualTo(5);
    }
    @Test void configuredModelChunkAndThresholdAreUsedWithoutDateRules() {
        // 청크·임계값·모델을 환경변수로 바꾸며 날짜 필터는 설정하지 않는다.
        var env = new MockEnvironment().withProperty("MEETODO_RAG_ENABLED", "true")
                .withProperty("MEETODO_RAG_CHUNK_TOKENS", "250")
                .withProperty("MEETODO_RAG_SIMILARITY_THRESHOLD", "0.42")
                .withProperty("MEETODO_EMBEDDING_MODEL", "custom-model");
        var properties = new RagProperties(env);
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.chunkTokens()).isEqualTo(250);
        assertThat(properties.similarityThreshold()).isEqualTo(0.42);
        assertThat(properties.embeddingModel()).isEqualTo("custom-model");
    }
    @Test void invalidThresholdRejectsSettingWithoutEchoingItsValue() {
        // 잘못 입력한 값이 비밀값일 수 있으므로 설정 이름만 오류에 표시한다.
        assertThatThrownBy(() -> new RagProperties(new MockEnvironment()
                .withProperty("MEETODO_RAG_SIMILARITY_THRESHOLD", "private-setting")))
                .hasMessageContaining("MEETODO_RAG_SIMILARITY_THRESHOLD").hasMessageNotContaining("private-setting");
    }
    @Test void outOfRangeThresholdIsRejected() {
        // 코사인 검색 API 범위를 벗어난 임계값을 시작 시 거절한다.
        assertThatThrownBy(() -> new RagProperties(new MockEnvironment()
                .withProperty("MEETODO_RAG_SIMILARITY_THRESHOLD", "1.2"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void nonPositiveChunkTargetIsRejected() {
        // 청크 목표0은 분할 오류나 빈 색인을 만들 수 없어 거절한다.
        assertThatThrownBy(() -> new RagProperties(new MockEnvironment()
                .withProperty("MEETODO_RAG_CHUNK_TOKENS", "0"))).isInstanceOf(IllegalArgumentException.class);
    }
}
