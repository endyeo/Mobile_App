package com.flower.backend.chatbot.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChatbotConfig {

    @Bean(name = "chatbotSseExecutor")
    public Executor chatbotSseExecutor(
            @Value("${chatbot.sse.executor.core-pool-size:4}") int corePoolSize,
            @Value("${chatbot.sse.executor.max-pool-size:8}") int maxPoolSize,
            @Value("${chatbot.sse.executor.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("chatbot-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
