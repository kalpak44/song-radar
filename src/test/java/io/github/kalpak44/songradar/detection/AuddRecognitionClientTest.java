package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuddRecognitionClientTest {

  @Test
  void aConfiguredTokenEnablesRecognition() {
    assertThat(new AuddRecognitionClient(" token ").isConfigured()).isTrue();
  }

  @Test
  void aMissingOrBlankTokenDisablesRecognition() {
    assertThat(new AuddRecognitionClient(null).isConfigured()).isFalse();
    assertThat(new AuddRecognitionClient("   ").isConfigured()).isFalse();
  }
}
