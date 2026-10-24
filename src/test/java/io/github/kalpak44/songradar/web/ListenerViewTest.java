package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ListenerViewTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  @Test
  void formatsAnActiveScheduleAndRecordingTimes() {
    ListenerView view =
        ListenerView.of(listener(ListenerStatus.ACTIVE, NOW, NOW.plusSeconds(180)), ZoneOffset.UTC);

    assertTrue(view.active());
    assertEquals("Every 3 minutes", view.interval());
    assertEquals("12 seconds recording", view.duration());
    assertEquals("Oct 24, 12:00", view.lastRecording());
    assertEquals("Oct 24, 12:03", view.nextRecording());
  }

  @Test
  void pausedListenerHasNoNextRunAndNeverRecordedFallback() {
    ListenerView view =
        ListenerView.of(listener(ListenerStatus.PAUSED, null, null), ZoneOffset.UTC);

    assertFalse(view.active());
    assertEquals("Paused", view.nextRecording());
    assertEquals("Never recorded", view.lastRecording());
  }

  private static PlaylistListener listener(
      ListenerStatus status, Instant lastRecording, Instant nextRecording) {
    return new PlaylistListener(
        7L,
        4L,
        "MTV",
        "MTV Hits",
        "https://e.com/mtv",
        180,
        12,
        status,
        NOW.minusSeconds(3600),
        NOW,
        lastRecording,
        nextRecording);
  }
}
