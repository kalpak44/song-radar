package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public record ListenerView(
    long id,
    String name,
    String channelName,
    String interval,
    String duration,
    String statusLabel,
    boolean active,
    String lastRecording,
    String nextRecording) {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

  public static ListenerView of(PlaylistListener listener, ZoneId zone) {
    boolean active = listener.status() == ListenerStatus.ACTIVE;
    return new ListenerView(
        listener.id(),
        listener.name(),
        listener.channelName(),
        "Every " + minutes(listener.recordingIntervalSeconds()),
        seconds(listener.recordingDurationSeconds()) + " recording",
        active ? "Active" : "Paused",
        active,
        format(listener.lastRecordingAt(), zone, "Never recorded"),
        active ? format(listener.nextRecordingAt(), zone, "Scheduling") : "Paused");
  }

  public static List<ListenerView> of(List<PlaylistListener> listeners, ZoneId zone) {
    return listeners.stream().map(listener -> of(listener, zone)).toList();
  }

  private static String minutes(int intervalSeconds) {
    int value = intervalSeconds / 60;
    return value + (value == 1 ? " minute" : " minutes");
  }

  private static String seconds(int value) {
    return value + (value == 1 ? " second" : " seconds");
  }

  private static String format(Instant value, ZoneId zone, String fallback) {
    return value == null ? fallback : TIME.format(value.atZone(zone));
  }
}
