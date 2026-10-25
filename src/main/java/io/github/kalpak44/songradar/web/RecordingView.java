package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public record RecordingView(
    long id,
    String recordedAt,
    Instant recordedAtInstant,
    String duration,
    String size,
    boolean playable,
    String errorMessage) {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss", Locale.ENGLISH);

  public static List<RecordingView> of(List<ListenerRecording> recordings, ZoneId zone) {
    return recordings.stream().map(recording -> of(recording, zone)).toList();
  }

  public static RecordingView of(ListenerRecording recording, ZoneId zone) {
    return new RecordingView(
        recording.id(),
        TIME.format(recording.startedAt().atZone(zone)),
        recording.startedAt(),
        formatDuration(Duration.ofMillis(recording.durationMillis())),
        formatSize(recording.sizeBytes()),
        recording.status() == RecordingStatus.COMPLETED && recording.filePath() != null,
        recording.errorMessage());
  }

  private static String formatDuration(Duration value) {
    long seconds = Math.max(0, value.getSeconds());
    if (seconds < 60) {
      return seconds + (seconds == 1 ? " second" : " seconds");
    }
    long minutes = seconds / 60;
    long remainder = seconds % 60;
    return minutes + " min " + String.format(Locale.ROOT, "%02d sec", remainder);
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    if (bytes < 1024 * 1024) {
      return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }
    return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
  }
}
