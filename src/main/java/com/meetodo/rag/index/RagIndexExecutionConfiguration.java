package com.meetodo.rag.index;

import com.meetodo.rag.config.RagEnabled;
import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@Conditional(RagEnabled.class)
public class RagIndexExecutionConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "ragIndexExecutor")
    Executor ragIndexExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("rag-index-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }
}
