package io.github.kalpak44.songradar.config;

import io.github.kalpak44.songradar.detection.AuddRecognitionClient;
import io.github.kalpak44.songradar.detection.DisabledRecognitionClient;
import io.github.kalpak44.songradar.detection.RecognitionProvider;
import io.github.kalpak44.songradar.detection.RecognitionProviders;
import io.github.kalpak44.songradar.detection.RecognitionSettingsRepository;
import io.github.kalpak44.songradar.detection.ShazamRecognitionClient;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds every recognition provider up front and hands back the one façade the application uses.
 * Which of them answers a given recording is a stored setting, not a wiring decision, so they are
 * assembled here rather than being components the container would have to choose between.
 */
@Configuration
public class RecognitionConfiguration {

  /**
   * One bean, deliberately typed as the concrete class: it is both the {@link
   * io.github.kalpak44.songradar.detection.RecognitionClient} the detection service consumes and
   * the provider registry the settings page reads, and a second bean aliasing it would make every
   * lookup of this type ambiguous.
   */
  @Bean
  RecognitionProviders recognitionProviders(
      RecognitionSettingsRepository settings,
      @Value("${songradar.recognition.audd.api-token:}") String auddApiToken) {
    return new RecognitionProviders(
        settings,
        Map.of(
            RecognitionProvider.AUDD, new AuddRecognitionClient(auddApiToken),
            RecognitionProvider.SHAZAM, new ShazamRecognitionClient(),
            RecognitionProvider.NONE, new DisabledRecognitionClient()));
  }
}
