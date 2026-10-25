package io.github.kalpak44.songradar.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
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
}
