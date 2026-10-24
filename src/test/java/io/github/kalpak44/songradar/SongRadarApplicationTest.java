package io.github.kalpak44.songradar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class SongRadarApplicationTest {

  @Test
  void mainStartsTheApplicationWithItsArguments() {
    String[] args = {"--spring.profiles.active=test"};
    AtomicBoolean started = new AtomicBoolean();

    try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
      springApplication
          .when(() -> SpringApplication.run(SongRadarApplication.class, args))
          .thenAnswer(
              invocation -> {
                started.set(true);
                return null;
              });

      SongRadarApplication.main(args);
    }

    assertTrue(started.get(), "main should start Spring Boot with the application class");
  }
}
