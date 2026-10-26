package io.github.kalpak44.songradar.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.kalpak44.songradar.detection.RecognitionClient;
import io.github.kalpak44.songradar.detection.RecognitionProvider;
import io.github.kalpak44.songradar.detection.RecognitionProviders;
import io.github.kalpak44.songradar.detection.RecognitionSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exercises the real wiring rather than a mock of it. Every other test replaces the recognition
 * bean, so a duplicate definition here would only surface as a context that refuses to start after
 * deployment — which is exactly what happened once.
 */
class RecognitionConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of())
          .withUserConfiguration(SettingsTestConfiguration.class, RecognitionConfiguration.class);

  @Test
  void oneBeanSatisfiesBothTheClientAndTheRegistry() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          // Both injection points must resolve without a qualifier.
          assertThat(context.getBeanNamesForType(RecognitionProviders.class)).hasSize(1);
          assertThat(context.getBeanNamesForType(RecognitionClient.class)).hasSize(1);
          assertThat(context.getBean(RecognitionClient.class))
              .isSameAs(context.getBean(RecognitionProviders.class));
        });
  }

  @Test
  void everyProviderIsBuiltSoAnyOfThemCanBeSelectedAtRuntime() {
    runner.run(
        context -> {
          RecognitionProviders providers = context.getBean(RecognitionProviders.class);
          for (RecognitionProvider provider : RecognitionProvider.values()) {
            // Throws if a provider has no client behind it.
            providers.isConfigured(provider);
          }
          assertThat(providers.isConfigured(RecognitionProvider.SHAZAM)).isTrue();
          assertThat(providers.isConfigured(RecognitionProvider.NONE)).isFalse();
        });
  }

  @Test
  void auddIsUnusableUntilItsTokenIsSet() {
    runner.run(
        context ->
            assertThat(
                    context
                        .getBean(RecognitionProviders.class)
                        .isConfigured(RecognitionProvider.AUDD))
                .isFalse());

    runner
        .withPropertyValues("songradar.recognition.audd.api-token=a-token")
        .run(
            context ->
                assertThat(
                        context
                            .getBean(RecognitionProviders.class)
                            .isConfigured(RecognitionProvider.AUDD))
                    .isTrue());
  }

  @Configuration(proxyBeanMethods = false)
  static class SettingsTestConfiguration {

    @Bean
    RecognitionSettingsRepository recognitionSettingsRepository() {
      return mock(RecognitionSettingsRepository.class);
    }
  }
}
