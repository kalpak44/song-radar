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
  private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
  private final Set<Long> running = ConcurrentHashMap.newKeySet();

  public ListenerScheduler(
      TaskScheduler taskScheduler,
      PlaylistListenerRepository listeners,
      RecordingService recordingService,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.listeners = listeners;
    this.recordingService = recordingService;
    this.clock = clock;
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
      schedule(listener.id(), next == null || next.isBefore(now) ? now : next);
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
    RecordingAttempt attempt = recordingService.record(listener);
    Instant updatedAt = clock.instant();
    Instant next = updatedAt.plusSeconds(listener.recordingIntervalSeconds());
    listeners.updateAfterRecording(listener.id(), attempt.startedAt(), next, updatedAt);
    listeners
        .findById(listener.id())
        .filter(current -> current.status() == ListenerStatus.ACTIVE)
        .ifPresent(current -> schedule(current.id(), next));
  }
}
