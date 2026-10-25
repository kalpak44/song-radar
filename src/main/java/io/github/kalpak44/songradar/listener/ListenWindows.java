package io.github.kalpak44.songradar.listener;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The times of day a listener records, as a comma-separated list of {@code HH:mm-HH:mm} ranges. An
 * empty list means around the clock.
 *
 * <p>A range whose end is before its start runs over midnight, so 22:00-02:00 is four hours rather
 * than an error — a night-time window is the obvious thing to want and the awkward thing to write.
 */
public record ListenWindows(List<Window> windows) {

  private static final ListenWindows ALWAYS = new ListenWindows(List.of());

  public ListenWindows {
    windows = List.copyOf(windows);
  }

  public record Window(LocalTime start, LocalTime end) {

    boolean contains(LocalTime time) {
      if (start.equals(end)) {
        return true;
      }
      return end.isAfter(start)
          ? !time.isBefore(start) && time.isBefore(end)
          : !time.isBefore(start) || time.isBefore(end);
    }

    @Override
    public String toString() {
      return String.format(
          "%02d:%02d-%02d:%02d",
          start.getHour(), start.getMinute(), end.getHour(), end.getMinute());
    }
  }

  /** Records around the clock. */
  public static ListenWindows always() {
    return ALWAYS;
  }

  public boolean isAlways() {
    return windows.isEmpty();
  }

  /**
   * @throws IllegalArgumentException with a message meant for the person editing the listener
   */
  public static ListenWindows parse(String value) {
    if (value == null || value.isBlank()) {
      return ALWAYS;
    }
    List<Window> parsed = new ArrayList<>();
    for (String part : value.split(",")) {
      String range = part.trim();
      if (range.isEmpty()) {
        continue;
      }
      String[] ends = range.split("-");
      if (ends.length != 2) {
        throw new IllegalArgumentException(
            "Write each window as HH:mm-HH:mm, for example 09:00-13:00.");
      }
      parsed.add(new Window(time(ends[0].trim(), range), time(ends[1].trim(), range)));
    }
    return parsed.isEmpty() ? ALWAYS : new ListenWindows(parsed);
  }

  private static LocalTime time(String value, String range) {
    try {
      return LocalTime.parse(value.length() == 1 ? "0" + value + ":00" : value);
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException(range + " is not a time range. Use HH:mm-HH:mm.");
    }
  }

  public boolean isOpenAt(LocalTime time) {
    return windows.isEmpty() || windows.stream().anyMatch(window -> window.contains(time));
  }

  /**
   * The first moment at or after {@code from} that falls inside a window. Returns {@code from} when
   * it is already inside one, so a caller can use the result unconditionally.
   */
  public LocalDateTime nextOpening(LocalDateTime from) {
    if (isOpenAt(from.toLocalTime())) {
      return from;
    }
    return windows.stream()
        .map(window -> onOrAfter(from, window.start()))
        .min(LocalDateTime::compareTo)
        .orElse(from);
  }

  private static LocalDateTime onOrAfter(LocalDateTime from, LocalTime start) {
    LocalDateTime today = from.toLocalDate().atTime(start);
    return today.isBefore(from) ? today.plusDays(1) : today;
  }

  /** The stored form, which is also what the editor shows. */
  public String stored() {
    return windows.stream().map(Window::toString).collect(Collectors.joining(", "));
  }
}
