package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecognitionProviderTest {

  @Test
  void providersAreReadBackFromTheIdTheyAreStoredUnder() {
    assertThat(RecognitionProvider.of("audd")).isEqualTo(RecognitionProvider.AUDD);
    assertThat(RecognitionProvider.of(" Shazam ")).isEqualTo(RecognitionProvider.SHAZAM);
    assertThat(RecognitionProvider.of("none")).isEqualTo(RecognitionProvider.NONE);
  }

  /** A posted form carries whatever the browser sent, so the unknown case has to be an error. */
  @Test
  void anythingElseIsRejected() {
    assertThatThrownBy(() -> RecognitionProvider.of("acrcloud"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RecognitionProvider.of(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyProviderStoresAndDisplaysSomething() {
    for (RecognitionProvider provider : RecognitionProvider.values()) {
      assertThat(provider.id()).isNotBlank();
      assertThat(provider.label()).isNotBlank();
      assertThat(RecognitionProvider.of(provider.id())).isEqualTo(provider);
    }
  }
}
