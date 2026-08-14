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

  /**
   * Single-worker executor for asynchronous Builder Goal account launches.
   *
   * <p>A single local executor port cannot support two concurrent relaunches. Only one worker
   * processes launches at a time. Rejected submissions are handled at the service layer with clear
   * HTTP 503 responses.
   *
   * <p>Account launches can wait for the local Micro6 executor to become dispatch-ready for up to
   * the 90-minute server-side watchdog. Keeping them on a dedicated pool prevents long login waits
   * from exhausting the patch-loop workers or the request threads.
   */
  @Bean(name = "builderGoalLaunchExecutor")
  public Executor builderGoalLaunchExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("builder-goal-launch-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }
}
