package io.github.kalpak44.songradar.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ListeningScheduleTest {

  /** Windows are wall-clock where the listener lives, while the clock the app runs on is UTC. */
  @Test
  void readsWindowsInTheConfiguredZoneRatherThanUtc() {
    ListeningSchedule sofia = new ListeningSchedule("Europe/Sofia");
    // 07:30 UTC is 09:30 in Sofia, inside a 09:00-13:00 window and outside it in UTC.
    Instant at = Instant.parse("2026-10-25T07:30:00Z");

    assertThat(sofia.isOpen("09:00-13:00", at)).isTrue();
    assertThat(new ListeningSchedule("UTC").isOpen("09:00-13:00", at)).isFalse();
  }

  @Test
  void anEmptyWindowListIsAlwaysOpen() {
    ListeningSchedule schedule = new ListeningSchedule("UTC");
    Instant at = Instant.parse("2026-10-25T03:00:00Z");

    assertThat(schedule.isOpen(null, at)).isTrue();
    assertThat(schedule.nextOpening("", at)).isEqualTo(at);
  }

  @Test
  void movesAClosedInstantToTheNextOpening() {
    ListeningSchedule schedule = new ListeningSchedule("UTC");

    assertThat(schedule.nextOpening("00:00-06:00", Instant.parse("2026-10-25T07:30:00Z")))
        .isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
  }

  @Test
  void unreadableWindowsFallBackToAlwaysOpen() {
    ListeningSchedule schedule = new ListeningSchedule("");

    assertThat(schedule.zone().getId()).isEqualTo("UTC");
  }
}
