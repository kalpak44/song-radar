package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecognitionProvidersTest {

  private RecognitionSettingsRepository settings;
  private RecognitionClient audd;
  private RecognitionClient shazam;
  private RecognitionProviders providers;

  @BeforeEach
  void setUp() {
    settings = mock(RecognitionSettingsRepository.class);
    audd = mock(RecognitionClient.class);
    shazam = mock(RecognitionClient.class);
    providers =
        new RecognitionProviders(
            settings,
            Map.of(
                RecognitionProvider.AUDD, audd,
                RecognitionProvider.SHAZAM, shazam,
                RecognitionProvider.NONE, new DisabledRecognitionClient()));
  }

  @Test
  void itDelegatesToWhicheverProviderIsSelected() {
    Path recording = Path.of("capture.wav");
    when(settings.findProvider()).thenReturn(RecognitionProvider.SHAZAM);
    when(shazam.recognize(recording)).thenReturn(Map.of("title", "Song"));
    when(shazam.name()).thenReturn("Shazam");

    assertThat(providers.name()).isEqualTo("Shazam");
    assertThat(providers.recognize(recording)).containsEntry("title", "Song");
    verify(audd, org.mockito.Mockito.never()).recognize(recording);
  }

  /**
   * The setting is read per call rather than cached, which is what lets the settings page take
   * effect on the next recording instead of the next restart.
   */
  @Test
  void aSwitchTakesEffectWithoutRebuildingAnything() {
    when(settings.findProvider())
        .thenReturn(RecognitionProvider.AUDD)
        .thenReturn(RecognitionProvider.SHAZAM);
    when(audd.name()).thenReturn("AudD");
    when(shazam.name()).thenReturn("Shazam");

    assertThat(providers.name()).isEqualTo("AudD");
    assertThat(providers.name()).isEqualTo("Shazam");
  }

  @Test
  void disablingRecognitionReportsItselfRatherThanCallingAProvider() {
    when(settings.findProvider()).thenReturn(RecognitionProvider.NONE);

    assertThat(providers.isConfigured()).isFalse();
    assertThat(providers.unavailableReason()).contains("settings page");
    assertThatThrownBy(() -> providers.recognize(Path.of("capture.wav")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aProviderCanBeAskedWhetherItIsReadyBeforeBeingSelected() {
    when(audd.isConfigured()).thenReturn(false);
    when(shazam.isConfigured()).thenReturn(true);

    assertThat(providers.isConfigured(RecognitionProvider.AUDD)).isFalse();
    assertThat(providers.isConfigured(RecognitionProvider.SHAZAM)).isTrue();
  }

  @Test
  void selectingAProviderPersistsIt() {
    providers.select(RecognitionProvider.SHAZAM);

    verify(settings).saveProvider(RecognitionProvider.SHAZAM);
  }
}
