package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class SoundDetectionServiceTest {

  private SoundDetectionRepository detections;
  private AuddRecognitionClient recognition;
  private TaskExecutor executor;
  private SoundDetectionService service;

  @BeforeEach
  void setUp() {
    detections = mock(SoundDetectionRepository.class);
    recognition = mock(AuddRecognitionClient.class);
    executor = mock(TaskExecutor.class);
    service =
        new SoundDetectionService(
            Path.of("/tmp/songradar-recordings"),
            detections,
            mock(ListenerRecordingRepository.class),
            recognition,
            executor,
            Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void repeatedTrackUpdatesExistingRowInsteadOfCreatingADuplicate() {
    Instant firstDetected = Instant.parse("2026-10-20T10:00:00Z");
    SoundDetection existing =
        new SoundDetection(
            9L,
            13L,
            DetectionStatus.MATCHED,
            "AudD",
            "artist|song",
            "Song",
            "Artist",
            null,
            null,
            null,
            firstDetected,
            firstDetected,
            2,
            null);
    SoundDetection pending = pending(10L, 14L);
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.of(existing));

    service.saveMatched(pending, Map.of("title", "Song", "artist", "Artist"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(9L);
    assertThat(saved.getValue().recordingId()).isEqualTo(14L);
    assertThat(saved.getValue().detectionCount()).isEqualTo(3);
    assertThat(saved.getValue().firstDetectedAt()).isEqualTo(firstDetected);
    assertThat(saved.getValue().detectedAt()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));
    verify(detections).deleteById(10L);
  }

  @Test
  void firstTrackMatchKeepsThePendingRow() {
    SoundDetection pending = pending(10L, 14L);
    when(detections.findByTrackKey("artist|song")).thenReturn(Optional.empty());

    service.saveMatched(pending, Map.of("title", "Song", "artist", "Artist"));

    ArgumentCaptor<SoundDetection> saved = ArgumentCaptor.forClass(SoundDetection.class);
    verify(detections).save(saved.capture());
    assertThat(saved.getValue().id()).isEqualTo(10L);
    assertThat(saved.getValue().trackKey()).isEqualTo("artist|song");
    assertThat(saved.getValue().status()).isEqualTo(DetectionStatus.MATCHED);
  }

  @Test
  void manualDetectionQueuesACompletedRecordingOnce() {
    ListenerRecording recording = recording(14L);
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.empty());
    when(detections.save(any(SoundDetection.class)))
        .thenReturn(
            new SoundDetection(
                15L,
                14L,
                DetectionStatus.PENDING,
                "AudD",
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-25T10:00:00Z"),
                Instant.parse("2026-10-25T10:00:00Z"),
                1,
                null));

    assertThat(service.manualAttempt(recording))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.QUEUED);

    verify(executor).execute(any(Runnable.class));
  }

  @Test
  void manualDetectionDoesNotQueueASecondPendingRequest() {
    when(recognition.isConfigured()).thenReturn(true);
    when(detections.findFirstByRecordingIdAndStatusOrderByIdDesc(14L, DetectionStatus.PENDING))
        .thenReturn(Optional.of(pending(10L, 14L)));

    assertThat(service.manualAttempt(recording(14L)))
        .isEqualTo(SoundDetectionService.ManualAttemptResult.ALREADY_PENDING);

    verify(detections, org.mockito.Mockito.never()).save(any(SoundDetection.class));
    verify(executor, org.mockito.Mockito.never()).execute(any(Runnable.class));
  }

  private static SoundDetection pending(long id, long recordingId) {
    Instant now = Instant.parse("2026-10-25T09:00:00Z");
    return new SoundDetection(
        id,
        recordingId,
        DetectionStatus.PENDING,
        "AudD",
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        now,
        1,
        null);
  }

  private static ListenerRecording recording(long id) {
    Instant now = Instant.parse("2026-10-25T09:00:00Z");
    return new ListenerRecording(
        id,
        3L,
        "playlist/listener/capture.wav",
        now,
        now,
        RecordingStatus.COMPLETED,
        128,
        12_000,
        null);
  }
}
