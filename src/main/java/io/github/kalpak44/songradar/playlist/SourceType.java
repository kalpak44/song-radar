package io.github.kalpak44.songradar.playlist;

/** Where a playlist's contents live. */
public enum SourceType {
  URL("URL"),
  FILE("File");

  private final String label;

  SourceType(String label) {
    this.label = label;
  }

  /** Shown in the sources list. */
  public String getLabel() {
    return label;
  }
}
