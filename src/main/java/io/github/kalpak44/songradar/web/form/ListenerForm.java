package io.github.kalpak44.songradar.web.form;

import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

  private boolean active = true;

  public static ListenerForm from(PlaylistListener listener) {
    ListenerForm form = new ListenerForm();
    form.setName(listener.name());
    form.setChannelUrl(listener.channelUrl());
    form.setIntervalMinutes(listener.recordingIntervalSeconds() / 60);
    form.setDurationSeconds(listener.recordingDurationSeconds());
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

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }
}
