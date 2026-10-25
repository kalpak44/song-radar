package io.github.kalpak44.songradar.web.form;

import io.github.kalpak44.songradar.listener.ListenWindows;
import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

public class ListenerForm {

  @NotBlank(message = "Enter a listener name.")
  @Size(max = 200, message = "Use at most 200 characters.")
  private String name;

  @NotBlank(message = "Choose a channel.")
  private String channelUrl;

  @Min(value = 1, message = "Record at least once per minute.")
  @Max(value = 1440, message = "Use an interval of at most 1,440 minutes.")
  private int intervalMinutes = 3;

  @Min(value = 1, message = "Record for at least one second.")
  @Max(value = 300, message = "Record for at most 300 seconds.")
  private int durationSeconds = 12;

  /** Off means around the clock, and the rows below are ignored rather than discarded. */
  private boolean scheduled;

  /**
   * Parallel lists, one entry per window row in the editor. The browser posts a pair of time inputs
   * per row, so a half-filled row arrives blank on one side and is rejected rather than silently
   * read as midnight.
   */
  private List<String> windowStart = new ArrayList<>();

  private List<String> windowEnd = new ArrayList<>();

  private boolean active = true;

  public static ListenerForm from(PlaylistListener listener) {
    ListenerForm form = new ListenerForm();
    form.setName(listener.name());
    form.setChannelUrl(listener.channelUrl());
    form.setIntervalMinutes(listener.recordingIntervalSeconds() / 60);
    form.setDurationSeconds(listener.recordingDurationSeconds());
    ListenWindows windows = ListenWindows.parse(listener.listenWindows());
    form.setScheduled(!windows.isAlways());
    windows
        .windows()
        .forEach(
            window -> {
              form.getWindowStart().add(window.start().toString());
              form.getWindowEnd().add(window.end().toString());
            });
    form.setActive(listener.status() == ListenerStatus.ACTIVE);
    return form;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getChannelUrl() {
    return channelUrl;
  }

  public void setChannelUrl(String channelUrl) {
    this.channelUrl = channelUrl;
  }

  public int getIntervalMinutes() {
    return intervalMinutes;
  }

  public void setIntervalMinutes(int intervalMinutes) {
    this.intervalMinutes = intervalMinutes;
  }

  public int getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(int durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public boolean isScheduled() {
    return scheduled;
  }

  public void setScheduled(boolean scheduled) {
    this.scheduled = scheduled;
  }

  public List<String> getWindowStart() {
    return windowStart;
  }

  public void setWindowStart(List<String> windowStart) {
    this.windowStart = windowStart == null ? new ArrayList<>() : windowStart;
  }

  public List<String> getWindowEnd() {
    return windowEnd;
  }

  public void setWindowEnd(List<String> windowEnd) {
    this.windowEnd = windowEnd == null ? new ArrayList<>() : windowEnd;
  }

  /**
   * The rows as the scheduler stores them. A row with neither side filled is dropped, because the
   * editor always renders one empty row for a listener that has no windows yet.
   */
  public String toStoredWindows() {
    if (!scheduled) {
      return "";
    }
    List<String> ranges = new ArrayList<>();
    for (int i = 0; i < Math.max(windowStart.size(), windowEnd.size()); i++) {
      String start = at(windowStart, i);
      String end = at(windowEnd, i);
      if (start.isEmpty() && end.isEmpty()) {
        continue;
      }
      ranges.add(start + "-" + end);
    }
    return String.join(", ", ranges);
  }

  private static String at(List<String> values, int index) {
    return index < values.size() && values.get(index) != null ? values.get(index).trim() : "";
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
