package com.meetodo.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagEnabledTests {
    private boolean enabled(boolean flag, String... profiles) {
        var environment = new MockEnvironment().withProperty("MEETODO_RAG_ENABLED", Boolean.toString(flag));
        environment.setActiveProfiles(profiles);
        var context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new RagEnabled().matches(context, null);
    }
    @Test void optOutDisablesRagEvenWithOpenaiProfile() {
        // 기존 실행은 플래그를 명시하지 않으면 색인 기능을 만들지 않는다.
        assertThat(enabled(false,"local","openai")).isFalse();
    }
    @Test void localWithoutOpenaiCannotCreateRagServices() {
        // 실제 VectorStore가 없는 조합에서 내부 서비스만 생기지 않게 한다.
        assertThat(enabled(true,"local")).isFalse();
    }
    @Test void enabledDockerOpenaiCreatesRagServices() {
        // 승인된 Docker/OpenAI 조합에서만 실제 검색 흐름을 활성화한다.
        assertThat(enabled(true,"docker","openai")).isTrue();
    }
    @Test void testProfileCanSupplyFakeVectorStore() {
        // 테스트는 실제 OpenAI 프로필 없이 fake 저장소를 주입할 수 있다.
        assertThat(enabled(true,"test")).isTrue();
    }
}
