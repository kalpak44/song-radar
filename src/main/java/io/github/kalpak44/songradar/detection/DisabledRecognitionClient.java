package io.github.kalpak44.songradar.detection;

import java.nio.file.Path;
import java.util.Map;

/**
 * The provider used when recognition is switched off. Recordings are still captured and kept; they
 * are simply marked unavailable rather than queued.
 */
public final class DisabledRecognitionClient implements RecognitionClient {

  @Override
  public String name() {
    return "None";
  }

  @Override
  public boolean isConfigured() {
    return false;
  }

  @Override
  public String unavailableReason() {
    return "Recognition is switched off. Choose a provider on the settings page to enable it.";
  }

  @Override
  public Map<String, String> recognize(Path file) {
    throw new IllegalStateException("Track recognition is disabled.");
  }
}
