package io.github.kalpak44.songradar.detection;

import java.nio.file.Path;
import java.util.Map;

/**
 * A track recognition provider. Implementations are selected by {@code songradar.recognition
 * .provider} and are the only thing {@link SoundDetectionService} knows about recognition.
 */
public interface RecognitionClient {

  /** The provider label stored on detections and shown in the activity feed. */
  String name();

  /** Whether this provider has everything it needs to be called. */
  boolean isConfigured();

  /**
   * Why this provider cannot run, shown on the recording that could not be identified. Only
   * meaningful while {@link #isConfigured()} is false.
   */
  String unavailableReason();

  /**
   * Identifies the track in a recording.
   *
   * @return the track fields, or an empty map when the provider recognised nothing
   * @throws RuntimeException when the provider could not be reached or refused the request
   */
  Map<String, String> recognize(Path file);
}
