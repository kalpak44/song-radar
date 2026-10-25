package io.github.kalpak44.songradar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ConfigurationTest {

  @Test
  void clockUsesUtc() {
    assertEquals(ZoneOffset.UTC, new ClockConfiguration().clock().getZone());
  }

  @Test
  void listenerSchedulerUsesTheConfiguredThreadCount() {
    ThreadPoolTaskScheduler scheduler = new SchedulerConfiguration().listenerTaskScheduler(3);

    assertNotNull(scheduler);
    assertEquals(3, scheduler.getPoolSize());
  }

  @Test
  void recognitionExecutorUsesTheConfiguredThreadCount() {
    ThreadPoolTaskExecutor executor =
        (ThreadPoolTaskExecutor) new SchedulerConfiguration().recognitionTaskExecutor(2);

    assertEquals(2, executor.getCorePoolSize());
    assertEquals(2, executor.getMaxPoolSize());
    assertEquals(1000, executor.getQueueCapacity());
    assertEquals("songradar-recognition-", executor.getThreadNamePrefix());
  }
}
