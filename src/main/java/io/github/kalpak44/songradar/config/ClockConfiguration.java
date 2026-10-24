package io.github.kalpak44.songradar.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

  /** Injected rather than called statically so a test can fix the time it records. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
