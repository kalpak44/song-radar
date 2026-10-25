package io.github.kalpak44.songradar.listener;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("listener_recordings")
public record ListenerRecording(
    @Id Long id,
    Long listenerId,
    String filePath,
    Instant startedAt,
    Instant completedAt,
    RecordingStatus status,
    long sizeBytes,
    long durationMillis,
    String errorMessage) {

  public static ListenerRecording completed(
      long listenerId,
      String filePath,
      Instant startedAt,
      Instant completedAt,
      long sizeBytes,
      long durationMillis) {
    return new ListenerRecording(
        null,
        listenerId,
        filePath,
        startedAt,
        completedAt,
        RecordingStatus.COMPLETED,
        sizeBytes,
        durationMillis,
        null);
  }

  public static ListenerRecording failed(
      long listenerId, Instant startedAt, Instant completedAt, String errorMessage) {
    return new ListenerRecording(
        null, listenerId, null, startedAt, completedAt, RecordingStatus.FAILED, 0, 0, errorMessage);
  }
}
