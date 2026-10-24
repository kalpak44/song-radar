package io.github.kalpak44.songradar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
}
