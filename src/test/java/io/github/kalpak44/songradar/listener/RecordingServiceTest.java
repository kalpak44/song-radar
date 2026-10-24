package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  private RecordingService service;

  @BeforeEach
  void setUp() {
    client = mock(StreamRecordingClient.class);
    recordings = mock(ListenerRecordingRepository.class);
    service = new RecordingService(root, client, recordings, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void completedCaptureStoresRelativeMetadata() throws Exception {
    when(client.capture(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Path directory = invocation.getArgument(1);
              Files.createDirectories(directory);
              return Files.write(directory.resolve("capture.mp3"), new byte[] {1, 2, 3});
            });

    RecordingAttempt attempt = service.record(listener());

    assertTrue(attempt.completed());
    ArgumentCaptor<ListenerRecording> saved = ArgumentCaptor.forClass(ListenerRecording.class);
    verify(recordings).save(saved.capture());
    assertTrue(saved.getValue().filePath().endsWith("4/7/capture.mp3"));
    assertTrue(saved.getValue().sizeBytes() == 3);
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

  private static PlaylistListener listener() {
    return new PlaylistListener(
        7L,
        4L,
        "MTV",
        "MTV Hits",
        "https://e.com/mtv",
        180,
        12,
        ListenerStatus.ACTIVE,
        NOW,
        NOW,
        null,
        NOW.plusSeconds(180));
  }
}
