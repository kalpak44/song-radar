package io.github.kalpak44.songradar.listener;

import io.github.kalpak44.songradar.activity.ActivityKind;
import io.github.kalpak44.songradar.activity.ActivityLog;
import io.github.kalpak44.songradar.detection.SoundDetectionService;
import io.github.kalpak44.songradar.storage.StorageService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecordingService {

  private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

  private final Path root;
  private final StreamRecordingClient client;
  private final ListenerRecordingRepository recordings;
  private final StorageService storage;
  private final Clock clock;
  private final SoundDetectionService soundDetections;
  private final ActivityLog activity;

  @Autowired
  public RecordingService(
      @Value("${songradar.storage.recordings}") Path root,
      StreamRecordingClient client,
      ListenerRecordingRepository recordings,
      StorageService storage,
      Clock clock,
      SoundDetectionService soundDetections,
      ActivityLog activity) {
    this.activity = activity;
    this.root = root.toAbsolutePath().normalize();
    this.client = client;
    this.recordings = recordings;
    this.storage = storage;
    this.clock = clock;
    this.soundDetections = soundDetections;
  }

  public RecordingService(
      @Value("${songradar.storage.recordings}") Path root,
      StreamRecordingClient client,
      ListenerRecordingRepository recordings,
      StorageService storage,
      Clock clock) {
    this(root, client, recordings, storage, clock, null, ActivityLog.NONE);
  }

  public RecordingAttempt record(PlaylistListener listener) {
    Instant started = clock.instant();
    Path directory =
        root.resolve(listener.playlistSourceId().toString()).resolve(listener.id().toString());
    String stem = started.toEpochMilli() + "-" + UUID.randomUUID();
    try {
      StreamRecordingClient.Capture capture =
          client.capture(
              URI.create(listener.channelUrl()),
              directory,
              stem,
              Duration.ofSeconds(listener.recordingDurationSeconds()));
      Instant completed = clock.instant();
      ListenerRecording saved =
          recordings.save(
              ListenerRecording.completed(
                  listener.id(),
                  root.relativize(capture.path()).toString(),
                  started,
                  completed,
                  Files.size(capture.path()),
                  capture.durationMillis()));
      activity.record(
          ActivityKind.RECORDED,
          listener.name(),
          null,
          listener.channelName(),
          "Captured " + (capture.durationMillis() / 1000) + "s from the stream");
      if (soundDetections != null) {
        try {
          soundDetections.enqueue(saved);
        } catch (RuntimeException e) {
          log.warn("Could not queue sound detection for recording {}", saved.id(), e);
        }
      }
      try {
        enforceRetention(listener.id(), storage.recordsPerListener());
      } catch (IOException | RuntimeException e) {
        log.warn("Could not enforce recording retention for listener {}", listener.id(), e);
      }
      return new RecordingAttempt(started, true);
    } catch (IOException | RuntimeException e) {
      Instant completed = clock.instant();
      recordings.save(
          ListenerRecording.failed(listener.id(), started, completed, conciseMessage(e)));
      activity.record(
          ActivityKind.UNAVAILABLE,
          listener.name(),
          null,
          listener.channelName(),
          "Could not record the stream: " + conciseMessage(e));
      return new RecordingAttempt(started, false);
    }
  }

  private void enforceRetention(long listenerId, int keepCount) throws IOException {
    long excess = recordings.countCompletedByListenerId(listenerId) - keepCount;
    if (excess <= 0) {
      return;
    }
    for (ListenerRecording oldest :
        recordings.findOldestCompletedByListenerId(listenerId, excess)) {
      delete(oldest);
    }
  }

  /** Deletes the recording file before its metadata, refusing paths outside the storage root. */
  public void delete(ListenerRecording recording) throws IOException {
    if (recording.filePath() != null) {
      Path rootPath = root.toRealPath();
      Path candidate = root.resolve(recording.filePath()).normalize();
      if (!candidate.startsWith(root)) {
        throw new IOException("Recording path is outside the storage directory.");
      }
      if (Files.exists(candidate)) {
        Path file = candidate.toRealPath();
        if (!file.startsWith(rootPath) || !Files.isRegularFile(file)) {
          throw new IOException("Recording path is outside the storage directory.");
        }
        Files.delete(file);
      }
    }
    recordings.delete(recording);
  }

  /** Removes every recording file and its metadata, reporting files that could not be removed. */
  public DeletionSummary deleteAll() {
    int deleted = 0;
    int failed = 0;
    List<ListenerRecording> all = recordings.findAll();
    for (ListenerRecording recording : all) {
      try {
        delete(recording);
        deleted++;
      } catch (IOException e) {
        failed++;
      }
    }
    return new DeletionSummary(deleted, failed);
  }

  private static String conciseMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "The stream was unavailable.";
    }
    return message.length() <= 500 ? message : message.substring(0, 500);
  }

  public record DeletionSummary(int deleted, int failed) {}
}
