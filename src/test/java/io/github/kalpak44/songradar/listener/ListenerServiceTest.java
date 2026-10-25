package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.kalpak44.songradar.playlist.PlaylistChannel;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ListenerServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  private PlaylistListenerRepository repository;
  private ListenerService service;

  @BeforeEach
  void setUp() {
    repository = mock(PlaylistListenerRepository.class);
    service = new ListenerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void createsAnActiveListenerWithItsFirstRunScheduled() {
    PlaylistListener listener =
        service.create(
            4L,
            "  MTV Listener  ",
            new PlaylistChannel("MTV Hits", "https://e.com/mtv"),
            3,
            12,
            true);

    assertEquals("MTV Listener", listener.name());
    assertEquals(180, listener.recordingIntervalSeconds());
    assertEquals(12, listener.recordingDurationSeconds());
    assertEquals(ListenerStatus.ACTIVE, listener.status());
    assertEquals(NOW.plusSeconds(180), listener.nextRecordingAt());
  }

  @Test
  void updatingAPausedListenerPreservesHistoryAndClearsTheNextRun() {
    PlaylistListener existing = listener(ListenerStatus.ACTIVE, NOW.minusSeconds(60));

    PlaylistListener updated =
        service.update(
            existing, "Retro", new PlaylistChannel("VH1", "https://e.com/vh1"), 5, 15, false);

    assertEquals(existing.createdAt(), updated.createdAt());
    assertEquals(existing.lastRecordingAt(), updated.lastRecordingAt());
    assertEquals(ListenerStatus.PAUSED, updated.status());
    assertNull(updated.nextRecordingAt());
  }

  @Test
  void reEnablingStartsNowAndPausingClearsTheNextRun() {
    PlaylistListener paused = service.setActive(listener(ListenerStatus.ACTIVE, null), false);
    assertEquals(ListenerStatus.PAUSED, paused.status());
    assertNull(paused.nextRecordingAt());

    PlaylistListener active = service.setActive(paused, true);
    assertEquals(ListenerStatus.ACTIVE, active.status());
    assertEquals(NOW, active.nextRecordingAt());
  }

  @Test
  void delegatesScopedLookupListAndDelete() {
    PlaylistListener listener = listener(ListenerStatus.PAUSED, null);
    when(repository.findAllByPlaylistSourceIdOrderByCreatedAtAsc(4L)).thenReturn(List.of(listener));
    when(repository.findByIdAndPlaylistSourceId(7L, 4L)).thenReturn(Optional.of(listener));

    assertEquals(List.of(listener), service.findAll(4L));
    assertEquals(Optional.of(listener), service.find(4L, 7L));
    service.delete(listener);

    verify(repository).delete(listener);
  }

  private static PlaylistListener listener(ListenerStatus status, Instant lastRecording) {
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
        NOW.minusSeconds(120),
        lastRecording,
        status == ListenerStatus.ACTIVE ? NOW.plusSeconds(30) : null);
  }
}
