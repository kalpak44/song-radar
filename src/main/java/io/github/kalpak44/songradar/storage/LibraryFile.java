package io.github.kalpak44.songradar.storage;

import java.time.Instant;

public record LibraryFile(
    String name, String relativePath, boolean directory, long size, Instant modifiedAt) {
  public boolean playable() {
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    return !directory
        && (lower.endsWith(".mp3")
            || lower.endsWith(".wav")
            || lower.endsWith(".m4a")
            || lower.endsWith(".ogg")
            || lower.endsWith(".flac")
            || lower.endsWith(".aac"));
  }
}
