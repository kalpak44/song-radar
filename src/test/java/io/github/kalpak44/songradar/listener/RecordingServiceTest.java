package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.songradar.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RecordingServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  @TempDir Path root;

  private StreamRecordingClient client;
  private ListenerRecordingRepository recordings;
  private StorageService storage;
  private RecordingService service;

  @BeforeEach
  void setUp() {
    client = mock(StreamRecordingClient.class);
    recordings = mock(ListenerRecordingRepository.class);
    storage = mock(StorageService.class);
    when(storage.recordsPerListener()).thenReturn(20);
    service =
        new RecordingService(root, client, recordings, storage, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void completedCaptureStoresRelativeMetadata() throws Exception {
    when(client.capture(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Path directory = invocation.getArgument(1);
              Files.createDirectories(directory);
              Path file = Files.write(directory.resolve("capture.wav"), new byte[] {1, 2, 3});
              return new StreamRecordingClient.Capture(file, 12_000);
            });

    RecordingAttempt attempt = service.record(listener());

    assertTrue(attempt.completed());
    ArgumentCaptor<ListenerRecording> saved = ArgumentCaptor.forClass(ListenerRecording.class);
    verify(recordings).save(saved.capture());
    assertTrue(saved.getValue().filePath().endsWith("4/7/capture.wav"));
    assertTrue(saved.getValue().sizeBytes() == 3);
    assertTrue(saved.getValue().durationMillis() == 12_000);
  }

  @Test
  void unavailableStreamStoresAFailureInsteadOfStoppingTheScheduler() throws Exception {
    when(client.capture(any(), any(), any(), any())).thenThrow(new IOException("offline"));

    RecordingAttempt attempt = service.record(listener());

    assertFalse(attempt.completed());
    ArgumentCaptor<ListenerRecording> saved = ArgumentCaptor.forClass(ListenerRecording.class);
    verify(recordings).save(saved.capture());
    assertTrue(saved.getValue().status() == RecordingStatus.FAILED);
    assertTrue(saved.getValue().errorMessage().contains("offline"));
  }

  @Test
  void successfulCaptureDeletesOldestRecordingWhenRetentionLimitIsReached() throws Exception {
    Path oldFile = Files.write(root.resolve("old.wav"), new byte[] {1, 2, 3});
    ListenerRecording oldest =
        ListenerRecording.completed(7L, "old.wav", NOW.minusSeconds(60), NOW, 3, 12_000);
    when(storage.recordsPerListener()).thenReturn(1);
    when(recordings.countCompletedByListenerId(7L)).thenReturn(2L);
    when(recordings.findOldestCompletedByListenerId(7L, 1L)).thenReturn(java.util.List.of(oldest));
    when(client.capture(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Path directory = invocation.getArgument(1);
              Files.createDirectories(directory);
              Path file = Files.write(directory.resolve("new.wav"), new byte[] {4, 5, 6});
              return new StreamRecordingClient.Capture(file, 12_000);
            });

    RecordingAttempt attempt = service.record(listener());

    assertTrue(attempt.completed());
    assertFalse(Files.exists(oldFile));
    verify(recordings).delete(oldest);
  }

  @Test
  void deleteRemovesRecordingFileAndMetadata() throws Exception {
    Path audioFile = Files.write(root.resolve("capture.wav"), new byte[] {1, 2, 3});
    ListenerRecording recording =
        ListenerRecording.completed(7L, "capture.wav", NOW, NOW.plusSeconds(12), 3, 12_000);

    service.delete(recording);

    assertFalse(Files.exists(audioFile));
    verify(recordings).delete(recording);
  }

  @Test
  void deleteRejectsPathsOutsideStorageRoot() {
    ListenerRecording recording =
        ListenerRecording.completed(7L, "../outside.wav", NOW, NOW.plusSeconds(12), 3, 12_000);

    org.junit.jupiter.api.Assertions.assertThrows(
        IOException.class, () -> service.delete(recording));
  }

  @Test
  void deleteAllCleansAudioAndMetadataAndCountsFailures() throws Exception {
    Path audioFile = Files.write(root.resolve("capture.wav"), new byte[] {1, 2, 3});
    ListenerRecording saved =
        ListenerRecording.completed(7L, "capture.wav", NOW, NOW.plusSeconds(12), 3, 12_000);
    ListenerRecording unsafe =
        ListenerRecording.completed(7L, "../outside.wav", NOW, NOW.plusSeconds(12), 3, 12_000);
    when(recordings.findAll()).thenReturn(java.util.List.of(saved, unsafe));

    RecordingService.DeletionSummary result = service.deleteAll();

    assertFalse(Files.exists(audioFile));
    assertTrue(result.deleted() == 1);
    assertTrue(result.failed() == 1);
    verify(recordings).delete(saved);
    verify(recordings, times(0)).delete(unsafe);
  }

  private static PlaylistListener listener() {
    return new PlaylistListener(
        7L,
        4L,
        "MTV",
        "MTV Hits",
        "https://e.com/mtv",
        180,
        12,
        null,
        ListenerStatus.ACTIVE,
        NOW,
        NOW,
        null,
        NOW.plusSeconds(180));
  }
}
