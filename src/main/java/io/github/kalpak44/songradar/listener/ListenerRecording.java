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
    String errorMessage) {

  public static ListenerRecording completed(
      long listenerId, String filePath, Instant startedAt, Instant completedAt, long sizeBytes) {
    return new ListenerRecording(
        null,
        listenerId,
        filePath,
        startedAt,
        completedAt,
        RecordingStatus.COMPLETED,
        sizeBytes,
        null);
  }

  public static ListenerRecording failed(
      long listenerId, Instant startedAt, Instant completedAt, String errorMessage) {
    return new ListenerRecording(
        null, listenerId, null, startedAt, completedAt, RecordingStatus.FAILED, 0, errorMessage);
  }
}
