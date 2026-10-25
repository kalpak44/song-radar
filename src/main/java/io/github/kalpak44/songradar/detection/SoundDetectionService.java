package io.github.kalpak44.songradar.detection;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class SoundDetectionService {

  private static final Logger log = LoggerFactory.getLogger(SoundDetectionService.class);
  private static final String PROVIDER = "AudD";

  private final Path root;
  private final SoundDetectionRepository detections;
  private final ListenerRecordingRepository recordings;
  private final AuddRecognitionClient recognition;
  private final TaskExecutor executor;
  private final Clock clock;

  public SoundDetectionService(
      @Value("${songradar.storage.recordings}") Path root,
      SoundDetectionRepository detections,
      ListenerRecordingRepository recordings,
      AuddRecognitionClient recognition,
      @Qualifier("recognitionTaskExecutor") TaskExecutor executor,
      Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.detections = detections;
    this.recordings = recordings;
    this.recognition = recognition;
    this.executor = executor;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void resumePending() {
    List<SoundDetection> pending = detections.findAllByStatus(DetectionStatus.PENDING);
    if (recognition.isConfigured()) {
      pending.forEach(this::schedule);
    } else {
      pending.forEach(
          detection ->
              update(
                  detection,
                  DetectionStatus.UNAVAILABLE,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  detection.detectionCount(),
                  "Recognition is disabled. Set SONGRADAR_AUDD_API_TOKEN to enable it."));
    }
  }

  public synchronized void enqueue(ListenerRecording recording) {
    if (recording.id() == null || recording.filePath() == null) {
      return;
    }
    if (detections
        .findFirstByRecordingIdAndStatusOrderByIdDesc(recording.id(), DetectionStatus.PENDING)
        .isPresent()) {
      return;
    }
    DetectionStatus status =
        recognition.isConfigured() ? DetectionStatus.PENDING : DetectionStatus.UNAVAILABLE;
    SoundDetection saved =
        detections.save(
            new SoundDetection(
                null,
                recording.id(),
                status,
                PROVIDER,
                null,
                null,
                null,
                null,
                null,
                null,
                clock.instant(),
                clock.instant(),
                1,
                status == DetectionStatus.UNAVAILABLE
                    ? "Recognition is disabled. Set SONGRADAR_AUDD_API_TOKEN to enable it."
                    : null));
    if (status == DetectionStatus.PENDING) {
      schedule(saved);
    }
  }

  public synchronized ManualAttemptResult manualAttempt(ListenerRecording recording) {
    if (!recognition.isConfigured()) {
      return ManualAttemptResult.DISABLED;
    }
    if (recording.filePath() == null || recording.id() == null) {
      return ManualAttemptResult.INVALID_RECORDING;
    }
    if (detections
        .findFirstByRecordingIdAndStatusOrderByIdDesc(recording.id(), DetectionStatus.PENDING)
        .isPresent()) {
      return ManualAttemptResult.ALREADY_PENDING;
    }
    SoundDetection pending =
        detections.save(
            new SoundDetection(
                null,
                recording.id(),
                DetectionStatus.PENDING,
                PROVIDER,
                null,
                null,
                null,
                null,
                null,
                null,
                clock.instant(),
                clock.instant(),
                1,
                null));
    schedule(pending);
    return ManualAttemptResult.QUEUED;
  }

  public enum ManualAttemptResult {
    QUEUED,
    ALREADY_PENDING,
    DISABLED,
    INVALID_RECORDING
  }

  private void schedule(SoundDetection detection) {
    if (recognition.isConfigured()) {
      executor.execute(() -> recognize(detection));
    }
  }

  private void recognize(SoundDetection detection) {
    try {
      ListenerRecording recording =
          recordings
              .findById(detection.recordingId())
              .orElseThrow(() -> new IllegalStateException("The recording has been removed."));
      Path audio = root.resolve(recording.filePath()).normalize();
      if (!audio.startsWith(root) || !Files.isRegularFile(audio)) {
        throw new IllegalStateException("The recording audio file is unavailable.");
      }
      Map<String, String> result = recognition.recognize(audio);
      String title = result.get("title");
      String artist = result.get("artist");
      if (blank(title) || blank(artist)) {
        update(detection, DetectionStatus.NO_MATCH, null, null, null, null, null, null, 1, null);
      } else {
        saveMatched(detection, result);
      }
    } catch (Exception exception) {
      log.warn("Track recognition failed for recording {}", detection.recordingId(), exception);
      update(
          detection,
          DetectionStatus.FAILED,
          null,
          null,
          null,
          null,
          null,
          null,
          1,
          conciseMessage(exception));
    }
  }

  synchronized void saveMatched(SoundDetection pending, Map<String, String> result) {
    String title = result.get("title");
    String artist = result.get("artist");
    String key = trackKey(title, artist);
    Instant now = clock.instant();
    var existing = detections.findByTrackKey(key);
    if (existing.isPresent() && !existing.get().id().equals(pending.id())) {
      SoundDetection current = existing.get();
      detections.save(
          new SoundDetection(
              current.id(),
              pending.recordingId(),
              DetectionStatus.MATCHED,
              PROVIDER,
              key,
              first(result.get("title"), current.title()),
              first(result.get("artist"), current.artist()),
              first(result.get("album"), current.album()),
              first(result.get("releaseDate"), current.releaseDate()),
              first(result.get("trackUrl"), current.trackUrl()),
              current.firstDetectedAt(),
              now,
              current.detectionCount() + 1,
              null));
      detections.deleteById(pending.id());
      return;
    }
    detections.save(
        new SoundDetection(
            pending.id(),
            pending.recordingId(),
            DetectionStatus.MATCHED,
            PROVIDER,
            key,
            title,
            artist,
            result.get("album"),
            result.get("releaseDate"),
            result.get("trackUrl"),
            pending.firstDetectedAt(),
            now,
            pending.detectionCount(),
            null));
  }

  private void update(
      SoundDetection current,
      DetectionStatus status,
      String key,
      String title,
      String artist,
      String album,
      String releaseDate,
      String url,
      int count,
      String error) {
    detections.save(
        new SoundDetection(
            current.id(),
            current.recordingId(),
            status,
            PROVIDER,
            key,
            title,
            artist,
            album,
            releaseDate,
            url,
            current.firstDetectedAt(),
            clock.instant(),
            count,
            error));
  }

  public List<SoundDetection> page(int page, int pageSize) {
    return detections.findPage(pageSize, (long) (page - 1) * pageSize);
  }

  public long count() {
    return detections.countAll();
  }

  public long pendingCount() {
    return detections.countAllByStatus(DetectionStatus.PENDING);
  }

  public boolean isConfigured() {
    return recognition.isConfigured();
  }

  private static String trackKey(String title, String artist) {
    return normalize(artist) + "|" + normalize(title);
  }

  private static String normalize(String value) {
    String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD);
    return normalized
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .trim();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String first(String value, String fallback) {
    return blank(value) ? fallback : value;
  }

  private static String conciseMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "Recognition could not be completed.";
    }
    return message.length() <= 400 ? message : message.substring(0, 400);
  }
}
