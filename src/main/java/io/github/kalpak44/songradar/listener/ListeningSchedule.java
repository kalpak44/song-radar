package io.github.kalpak44.songradar.listener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Applies a listener's windows to real instants. The clock runs in UTC, while a window is written
 * as the wall-clock time of whoever set it, so the zone to read them in is configuration rather
 * than the clock's own.
 */
@Component
public class ListeningSchedule {

  private final ZoneId zone;

  public ListeningSchedule(@Value("${songradar.timezone:UTC}") String timezone) {
    this.zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone.trim());
  }

  public ZoneId zone() {
    return zone;
  }

  public boolean isOpen(String storedWindows, Instant at) {
    return ListenWindows.parse(storedWindows)
        .isOpenAt(LocalDateTime.ofInstant(at, zone).toLocalTime());
  }

  /** {@code from} itself when a window is already open, so callers need no branch. */
  public Instant nextOpening(String storedWindows, Instant from) {
    LocalDateTime local = LocalDateTime.ofInstant(from, zone);
    return ListenWindows.parse(storedWindows).nextOpening(local).atZone(zone).toInstant();
  }
}
