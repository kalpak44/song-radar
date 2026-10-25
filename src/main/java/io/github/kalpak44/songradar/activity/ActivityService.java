package io.github.kalpak44.songradar.activity;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Writes the activity feed and keeps it to the window the page shows. */
@Service
public class ActivityService implements ActivityLog {

  private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

  /** The page reads one day; anything older is kept a little longer and then dropped. */
  private static final Duration RETENTION = Duration.ofDays(7);

  private static final int PAGE_LIMIT = 500;

  private final ActivityEventRepository events;
  private final Clock clock;

  public ActivityService(ActivityEventRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  /**
   * A failure here must not take down the recording or download it is reporting on — the feed is an
   * account of the work, not part of it.
   */
  @Override
  public void record(
      ActivityKind kind, String listener, String track, String source, String message) {
    try {
      events.save(
          new ActivityEvent(
              null,
              clock.instant(),
              kind,
              trim(listener, 200),
              trim(track, 500),
              trim(source, 40),
              trim(message, 500)));
    } catch (RuntimeException exception) {
      log.warn("Could not record activity {}: {}", kind, exception.toString());
    }
  }

  public List<ActivityEvent> lastDay() {
    return events.findSince(clock.instant().minus(Duration.ofDays(1)), PAGE_LIMIT);
  }

  public int pageLimit() {
    return PAGE_LIMIT;
  }

  @Scheduled(cron = "0 30 3 * * *")
  public void prune() {
    int removed = events.deleteOlderThan(clock.instant().minus(RETENTION));
    if (removed > 0) {
      log.info("Pruned {} activity events older than {} days", removed, RETENTION.toDays());
    }
  }

  private static String trim(String value, int limit) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String single = value.replaceAll("\\s+", " ").trim();
    return single.length() <= limit ? single : single.substring(0, limit);
  }
}
