package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.songradar.activity.ActivityLog;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application test mocks SpringApplication.run, so nothing else here builds a context — a
 * component Spring cannot construct passes every other test and then fails at startup. This builds
 * the downloader the way the container does.
 */
class SourcedMusicDownloaderWiringTest {

  @Test
  void theContainerCanConstructTheDownloader() {
    new ApplicationContextRunner()
        .withUserConfiguration(Collaborators.class)
        .withBean(SourcedMusicDownloader.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SourcedMusicDownloader.class);
            });
  }

  @Configuration
  static class Collaborators {

    @Bean
    TrackSource source() {
      return new TrackSource() {
        @Override
        public String name() {
          return "stub";
        }

        @Override
        public Optional<SourceTrack> find(String artist, String title) {
          return Optional.empty();
        }
      };
    }

    @Bean
    ActivityLog activityLog() {
      return ActivityLog.NONE;
    }
  }
}
