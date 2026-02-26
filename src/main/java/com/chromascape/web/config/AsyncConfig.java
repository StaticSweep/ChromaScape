package com.chromascape.web.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configuration for asynchronous task execution.
 *
 * <p>Enables @Async annotation and provides a dedicated thread pool for patch loop execution.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

  /**
   * Thread pool executor for patch loop iteration processing.
   *
   * <p>Dedicated pool to avoid blocking main request threads during script runs and analysis.
   */
  @Bean(name = "patchLoopExecutor")
  public Executor patchLoopExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("patch-loop-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(60);
    executor.initialize();
    return executor;
  }
}
