package io.github.kalpak44.songradar.listener;

import io.github.kalpak44.songradar.playlist.PlaylistChannel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ListenerService {

  private final PlaylistListenerRepository listeners;
  private final Clock clock;
  private final ListeningSchedule schedule;

  public ListenerService(
      PlaylistListenerRepository listeners, Clock clock, ListeningSchedule schedule) {
    this.listeners = listeners;
    this.clock = clock;
    this.schedule = schedule;
  }

  public List<PlaylistListener> findAll(long playlistId) {
    return listeners.findAllByPlaylistSourceIdOrderByCreatedAtAsc(playlistId);
  }

  public List<PlaylistListener> findAll() {
    return listeners.findAll();
  }

  public Optional<PlaylistListener> find(long playlistId, long listenerId) {
    return listeners.findByIdAndPlaylistSourceId(listenerId, playlistId);
  }

  public PlaylistListener create(
      long playlistId,
      String name,
      PlaylistChannel channel,
      int intervalMinutes,
      int durationSeconds,
      String listenWindows,
      boolean active) {
    Instant now = clock.instant();
    int intervalSeconds = Math.multiplyExact(intervalMinutes, 60);
    ListenWindows windows = ListenWindows.parse(listenWindows);
    return listeners.save(
        new PlaylistListener(
            null,
            playlistId,
            name.trim(),
            channel.name(),
            channel.streamUrl(),
            intervalSeconds,
            durationSeconds,
            windows.stored(),
            active ? ListenerStatus.ACTIVE : ListenerStatus.PAUSED,
            now,
            now,
            null,
            active
                ? schedule.nextOpening(windows.stored(), now.plusSeconds(intervalSeconds))
                : null));
  }

  public PlaylistListener update(
      PlaylistListener existing,
      String name,
      PlaylistChannel channel,
      int intervalMinutes,
      int durationSeconds,
      String listenWindows,
      boolean active) {
    Instant now = clock.instant();
    int intervalSeconds = Math.multiplyExact(intervalMinutes, 60);
    ListenWindows windows = ListenWindows.parse(listenWindows);
    return listeners.save(
        new PlaylistListener(
            existing.id(),
            existing.playlistSourceId(),
            name.trim(),
            channel.name(),
            channel.streamUrl(),
            intervalSeconds,
            durationSeconds,
            windows.stored(),
            active ? ListenerStatus.ACTIVE : ListenerStatus.PAUSED,
            existing.createdAt(),
            now,
            existing.lastRecordingAt(),
            active
                ? schedule.nextOpening(windows.stored(), now.plusSeconds(intervalSeconds))
                : null));
  }

  public PlaylistListener setActive(PlaylistListener existing, boolean active) {
    Instant now = clock.instant();
    return listeners.save(
        new PlaylistListener(
            existing.id(),
            existing.playlistSourceId(),
            existing.name(),
            existing.channelName(),
            existing.channelUrl(),
            existing.recordingIntervalSeconds(),
            existing.recordingDurationSeconds(),
            existing.listenWindows(),
            active ? ListenerStatus.ACTIVE : ListenerStatus.PAUSED,
            existing.createdAt(),
            now,
            existing.lastRecordingAt(),
            active ? schedule.nextOpening(existing.listenWindows(), now) : null));
  }

  public void delete(PlaylistListener listener) {
    listeners.delete(listener);
  }
}
