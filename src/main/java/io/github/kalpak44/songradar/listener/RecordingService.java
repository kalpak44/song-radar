package io.github.kalpak44.songradar.listener;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecordingService {

  private final Path root;
  private final StreamRecordingClient client;
  private final ListenerRecordingRepository recordings;
  private final Clock clock;

  public RecordingService(
      @Value("${songradar.storage.recordings}") Path root,
      StreamRecordingClient client,
      ListenerRecordingRepository recordings,
      Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.client = client;
    this.recordings = recordings;
    this.clock = clock;
  }

  public RecordingAttempt record(PlaylistListener listener) {
    Instant started = clock.instant();
    Path directory =
        root.resolve(listener.playlistSourceId().toString()).resolve(listener.id().toString());
    String stem = started.toEpochMilli() + "-" + UUID.randomUUID();
    try {
      Path file =
          client.capture(
              URI.create(listener.channelUrl()),
              directory,
              stem,
              Duration.ofSeconds(listener.recordingDurationSeconds()));
      Instant completed = clock.instant();
      recordings.save(
          ListenerRecording.completed(
              listener.id(),
              root.relativize(file).toString(),
              started,
              completed,
              Files.size(file)));
      return new RecordingAttempt(started, true);
    } catch (IOException | IllegalArgumentException e) {
      Instant completed = clock.instant();
      recordings.save(
          ListenerRecording.failed(listener.id(), started, completed, conciseMessage(e)));
      return new RecordingAttempt(started, false);
    }
  }

  private static String conciseMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "The stream was unavailable.";
    }
    return message.length() <= 500 ? message : message.substring(0, 500);
  }
}
