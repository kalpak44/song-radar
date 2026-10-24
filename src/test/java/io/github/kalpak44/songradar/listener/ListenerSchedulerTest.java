package io.github.kalpak44.songradar.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

class ListenerSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  private TaskScheduler taskScheduler;
  private PlaylistListenerRepository listeners;
  private RecordingService recordings;
  private ListenerScheduler scheduler;
  private ScheduledFuture<Object> future;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    listeners = mock(PlaylistListenerRepository.class);
    recordings = mock(RecordingService.class);
    future = mock(ScheduledFuture.class);
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    scheduler =
        new ListenerScheduler(
            taskScheduler, listeners, recordings, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void restoresActiveListenersAndKeepsTheirFutureStart() {
    PlaylistListener listener = listener(ListenerStatus.ACTIVE, NOW.plusSeconds(45));
    when(listeners.findAllByStatus(ListenerStatus.ACTIVE)).thenReturn(List.of(listener));

    scheduler.restoreActiveListeners();

    verify(taskScheduler)
        .schedule(any(Runnable.class), org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(45)));
  }

  @Test
  void refreshOfAPausedListenerCancelsItsExistingTask() {
    scheduler.refresh(listener(ListenerStatus.ACTIVE, NOW.plusSeconds(45)));

    scheduler.refresh(listener(ListenerStatus.PAUSED, null));

    verify(future).cancel(false);
  }

  @Test
  void aRunRecordsUpdatesMetadataAndSchedulesTheNextRun() {
    PlaylistListener listener = listener(ListenerStatus.ACTIVE, NOW.plusSeconds(45));
    when(listeners.findById(7L)).thenReturn(Optional.of(listener));
    when(recordings.record(listener)).thenReturn(new RecordingAttempt(NOW, true));
    scheduler.refresh(listener);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(task.capture(), any(Instant.class));

    task.getValue().run();

    verify(recordings).record(listener);
    verify(listeners).updateAfterRecording(7L, NOW, NOW.plusSeconds(180), NOW);
    verify(taskScheduler, atLeast(2)).schedule(any(Runnable.class), any(Instant.class));
  }

  private static PlaylistListener listener(ListenerStatus status, Instant next) {
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
        null,
        next);
  }
}
