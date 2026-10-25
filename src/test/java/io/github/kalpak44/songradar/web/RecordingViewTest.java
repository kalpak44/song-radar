package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingViewTest {

  private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

  @Test
  void formatsShortAndLongDurations() {
    assertEquals("1 second", view(1, 1_000).duration());
    assertEquals("5 seconds", view(1, 5_000).duration());
    assertEquals("1 min 05 sec", view(1, 65_000).duration());
  }

  @Test
  void formatsByteKilobyteAndMegabyteSizes() {
    assertEquals("1023 B", view(1_023, 1_000).size());
    assertEquals("1.0 KB", view(1_024, 1_000).size());
    assertEquals("2.0 MB", view(2 * 1024 * 1024, 1_000).size());
  }

  @Test
  void convertsAWholeList() {
    List<RecordingView> views = RecordingView.of(List.of(recording(12, 1_000)), ZoneOffset.UTC);

    assertEquals(1, views.size());
    assertTrue(views.getFirst().playable());
    assertEquals("Oct 25, 2026 · 08:00:00", views.getFirst().recordedAt());
  }

  private static RecordingView view(long sizeBytes, long durationMillis) {
    return RecordingView.of(recording(sizeBytes, durationMillis), ZoneOffset.UTC);
  }

  private static ListenerRecording recording(long sizeBytes, long durationMillis) {
    return new ListenerRecording(
        14L,
        7L,
        "4/7/recording.wav",
        NOW,
        NOW.plusSeconds(1),
        RecordingStatus.COMPLETED,
        sizeBytes,
        durationMillis,
        null);
  }
}
