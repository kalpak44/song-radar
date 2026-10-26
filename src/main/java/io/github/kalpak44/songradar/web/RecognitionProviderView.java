package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.detection.RecognitionProvider;
import io.github.kalpak44.songradar.detection.RecognitionProviders;
import java.util.Arrays;
import java.util.List;

/**
 * One selectable provider on the settings page, carrying why it cannot be used so the page can say
 * so before it is chosen rather than after the next recording fails.
 */
public record RecognitionProviderView(
    String id, String label, String description, boolean selected, String warning) {

  public static List<RecognitionProviderView> of(RecognitionProviders providers) {
    RecognitionProvider active = providers.active();
    return Arrays.stream(RecognitionProvider.values())
        .map(
            provider ->
                new RecognitionProviderView(
                    provider.id(),
                    provider.label(),
                    describe(provider),
                    provider == active,
                    provider != RecognitionProvider.NONE && !providers.isConfigured(provider)
                        ? "Not ready to use. " + warn(provider)
                        : null))
        .toList();
  }

  private static String describe(RecognitionProvider provider) {
    return switch (provider) {
      case AUDD ->
          "Uploads the recording to the AudD API. Needs a paid plan or trial credit; recognition"
              + " stops once the request allowance runs out.";
      case SHAZAM ->
          "Fingerprints the recording here and sends only its spectral peaks, never the audio, to"
              + " the undocumented endpoint Shazam's own app uses. No account and no quota, but no"
              + " guarantees either: it can stop working without notice and is throttled per"
              + " address.";
      case NONE -> "Recordings are captured and kept, but nothing tries to identify them.";
    };
  }

  private static String warn(RecognitionProvider provider) {
    return switch (provider) {
      case AUDD -> "Set SONGRADAR_AUDD_API_TOKEN in the environment first.";
      case SHAZAM, NONE -> "";
    };
  }
}
