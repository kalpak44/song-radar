package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.storage.StorageService;
import io.github.kalpak44.songradar.storage.StorageSnapshot;
import java.util.Locale;

/** Presentation-ready, read-only storage metrics for the settings page. */
public record StorageView(
    String path,
    String capacityDescription,
    String total,
    String used,
    String available,
    String songRadar,
    String usedPercent,
    String songRadarPercent,
    String otherUsedPercent,
    boolean logicalCapacity) {

  private static final long BYTES_PER_TB = 1_000_000_000_000L;

  public static StorageView of(StorageSnapshot snapshot) {
    return new StorageView(
        snapshot.path().toString(),
        snapshot.logicalCapacity()
            ? "Logical capacity configured for this SongRadar volume."
            : "Capacity is read directly from the filesystem.",
        formatBytes(snapshot.totalBytes()),
        formatBytes(snapshot.usedBytes()),
        formatBytes(snapshot.availableBytes()),
        formatBytes(snapshot.songRadarBytes()),
        percentage(snapshot.usedBytes(), snapshot.totalBytes()),
        percentage(snapshot.songRadarBytes(), snapshot.totalBytes()),
        percentage(
            Math.max(0, snapshot.usedBytes() - snapshot.songRadarBytes()), snapshot.totalBytes()),
        snapshot.logicalCapacity());
  }

  private static String formatBytes(long bytes) {
    if (bytes >= BYTES_PER_TB) {
      return formatNumber((double) bytes / BYTES_PER_TB) + " TB";
    }
    return formatNumber((double) bytes / StorageService.BYTES_PER_GB) + " GB";
  }

  private static String formatNumber(double value) {
    String pattern = value >= 100 ? "%.0f" : "%.1f";
    return String.format(Locale.ENGLISH, pattern, value);
  }

  private static String percentage(long part, long whole) {
    if (whole <= 0) {
      return "0";
    }
    double percent = Math.clamp(part * 100.0 / whole, 0.0, 100.0);
    return String.format(Locale.ENGLISH, "%.1f", percent);
  }
}
