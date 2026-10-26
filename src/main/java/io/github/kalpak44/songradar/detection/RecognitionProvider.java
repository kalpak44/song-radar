package io.github.kalpak44.songradar.detection;

import java.util.Locale;

/**
 * The providers a recording can be identified with, as stored and as shown on the settings page.
 */
public enum RecognitionProvider {
  AUDD("audd", "AudD"),
  SHAZAM("shazam", "Shazam"),
  NONE("none", "Disabled");

  private final String id;
  private final String label;

  RecognitionProvider(String id, String label) {
    this.id = id;
    this.label = label;
  }

  /** The value persisted in {@code recognition_settings}. */
  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  public static RecognitionProvider of(String id) {
    String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    for (RecognitionProvider provider : values()) {
      if (provider.id.equals(wanted)) {
        return provider;
      }
    }
    throw new IllegalArgumentException("Unknown recognition provider: " + id);
  }
}
