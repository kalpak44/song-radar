package io.github.kalpak44.songradar.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ListenWindowsTest {

  @Test
  void noWindowsMeansAroundTheClock() {
    assertThat(ListenWindows.parse(null).isAlways()).isTrue();
    assertThat(ListenWindows.parse("  ").isOpenAt(LocalTime.of(3, 0))).isTrue();
  }

  @Test
  void readsSeveralWindows() {
    ListenWindows windows = ListenWindows.parse("00:00-06:00, 09:00-13:00");

    assertThat(windows.isOpenAt(LocalTime.of(5, 59))).isTrue();
    assertThat(windows.isOpenAt(LocalTime.of(6, 0))).isFalse();
    assertThat(windows.isOpenAt(LocalTime.of(9, 0))).isTrue();
    assertThat(windows.isOpenAt(LocalTime.of(13, 0))).isFalse();
    assertThat(windows.stored()).isEqualTo("00:00-06:00, 09:00-13:00");
  }

  /** The obvious thing to want from a night-time window, and the awkward thing to write. */
  @Test
  void aWindowMayRunPastMidnight() {
    ListenWindows windows = ListenWindows.parse("22:00-02:00");

    assertThat(windows.isOpenAt(LocalTime.of(23, 30))).isTrue();
    assertThat(windows.isOpenAt(LocalTime.of(1, 0))).isTrue();
    assertThat(windows.isOpenAt(LocalTime.of(2, 0))).isFalse();
    assertThat(windows.isOpenAt(LocalTime.of(12, 0))).isFalse();
  }

  @Test
  void nextOpeningIsTheInstantItselfWhileAWindowIsOpen() {
    LocalDateTime inside = LocalDateTime.of(2026, 10, 25, 10, 15);

    assertThat(ListenWindows.parse("09:00-13:00").nextOpening(inside)).isEqualTo(inside);
  }

  @Test
  void nextOpeningMovesForwardToTheNearestWindow() {
    LocalDateTime closed = LocalDateTime.of(2026, 10, 25, 7, 30);

    assertThat(ListenWindows.parse("00:00-06:00, 09:00-13:00").nextOpening(closed))
        .isEqualTo(LocalDateTime.of(2026, 10, 25, 9, 0));
  }

  @Test
  void nextOpeningRollsOverToTomorrowWhenTheDayIsDone() {
    LocalDateTime evening = LocalDateTime.of(2026, 10, 25, 20, 0);

    assertThat(ListenWindows.parse("09:00-13:00").nextOpening(evening))
        .isEqualTo(LocalDateTime.of(2026, 10, 26, 9, 0));
  }

  @Test
  void rejectsSomethingThatIsNotATimeRange() {
    assertThatThrownBy(() -> ListenWindows.parse("morning"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HH:mm-HH:mm");
    assertThatThrownBy(() -> ListenWindows.parse("09:00-25:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
