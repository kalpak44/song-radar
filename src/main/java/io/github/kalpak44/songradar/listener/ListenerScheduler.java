package io.github.kalpak44.songradar.listener;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class ListenerScheduler {

  private final TaskScheduler taskScheduler;
  private final PlaylistListenerRepository listeners;
  private final RecordingService recordingService;
  private final Clock clock;
  private final ListeningSchedule schedule;
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
  private final Set<Long> running = ConcurrentHashMap.newKeySet();

  public ListenerScheduler(
      TaskScheduler taskScheduler,
      PlaylistListenerRepository listeners,
      RecordingService recordingService,
      Clock clock,
      ListeningSchedule schedule) {
    this.taskScheduler = taskScheduler;
    this.listeners = listeners;
    this.recordingService = recordingService;
    this.clock = clock;
    this.schedule = schedule;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void restoreActiveListeners() {
    listeners.findAllByStatus(ListenerStatus.ACTIVE).forEach(this::refresh);
  }

  public void refresh(PlaylistListener listener) {
    cancel(listener.id());
    if (listener.status() == ListenerStatus.ACTIVE) {
      Instant now = clock.instant();
      Instant next = listener.nextRecordingAt();
      Instant due = next == null || next.isBefore(now) ? now : next;
      schedule(listener.id(), schedule.nextOpening(listener.listenWindows(), due));
    }
  }

  public void cancel(long listenerId) {
    ScheduledFuture<?> future = scheduled.remove(listenerId);
    if (future != null) {
      future.cancel(false);
    }
  }

  private void schedule(long listenerId, Instant start) {
    // compute holds this key until the future is registered. An immediately due task
    // cannot remove itself before its handle reaches the map and leave a stale entry.
    scheduled.compute(listenerId, (_, _) -> taskScheduler.schedule(() -> run(listenerId), start));
  }

  private void run(long listenerId) {
    scheduled.remove(listenerId);
    if (!running.add(listenerId)) {
      return;
    }
    try {
      listeners
          .findById(listenerId)
          .filter(listener -> listener.status() == ListenerStatus.ACTIVE)
          .ifPresent(this::recordAndScheduleNext);
    } finally {
      running.remove(listenerId);
    }
  }

  private void recordAndScheduleNext(PlaylistListener listener) {
    Instant startedAt = clock.instant();
    // The window can close between a task being scheduled and it running — a listener paused
    // and resumed, or simply an interval that steps over the end of the window.
    if (!schedule.isOpen(listener.listenWindows(), startedAt)) {
      Instant reopens = schedule.nextOpening(listener.listenWindows(), startedAt);
      listeners.updateAfterRecording(listener.id(), listener.lastRecordingAt(), reopens, startedAt);
      schedule(listener.id(), reopens);
      return;
    }
    RecordingAttempt attempt = recordingService.record(listener);
    Instant updatedAt = clock.instant();
    Instant next =
        schedule.nextOpening(
            listener.listenWindows(), updatedAt.plusSeconds(listener.recordingIntervalSeconds()));
    listeners.updateAfterRecording(listener.id(), attempt.startedAt(), next, updatedAt);
    listeners
        .findById(listener.id())
        .filter(current -> current.status() == ListenerStatus.ACTIVE)
        .ifPresent(current -> schedule(current.id(), next));
  }
}
