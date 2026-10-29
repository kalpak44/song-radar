package io.github.kalpak44.songradar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulerConfiguration {

  @Bean
  ThreadPoolTaskScheduler listenerTaskScheduler(
      @Value("${songradar.listeners.scheduler-threads:2}") int threadCount) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(threadCount);
    scheduler.setThreadNamePrefix("songradar-listener-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    return scheduler;
  }

  /**
   * One thread, and a queue of one: an import is a long run of downloads, and two of them at once
   * would compete for the same yt-dlp and the same bandwidth rather than finish any sooner.
   */
  @Bean("importTaskExecutor")
  TaskExecutor importTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("songradar-import-");
    executor.setWaitForTasksToCompleteOnShutdown(false);
    return executor;
  }

  @Bean("recognitionTaskExecutor")
  TaskExecutor recognitionTaskExecutor(
      @Value("${songradar.recognition.worker-threads:1}") int threadCount) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threadCount);
    executor.setMaxPoolSize(threadCount);
    executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("songradar-recognition-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    return executor;
  }
}
