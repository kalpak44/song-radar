package io.github.kalpak44.songradar.library;

import io.github.kalpak44.songradar.activity.ActivityKind;
import io.github.kalpak44.songradar.activity.ActivityLog;
import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Fills the library from a YouTube Music link rather than from a listener's recordings — a playlist
 * or a single track, named by YouTube Music and then looked for across every source.
 *
 * <p>The link only says which songs are wanted. Each one is still searched for through the ordered
 * chain, so a track Audius carries at 320 kbps is taken from there rather than from the link it
 * arrived on; YouTube Music is third in that chain and picks up most of the rest. Only when no
 * catalogue can place a track by name is the exact video behind the link downloaded, which is the
 * one case where searching could silently substitute a different recording.
 */
@Service
public class PlaylistImportService {

  private static final Logger log = LoggerFactory.getLogger(PlaylistImportService.class);

  /** Imports land here rather than in a listener folder; nothing recorded them. */
  public static final String FOLDER = "YouTube Music";

  private static final Set<String> HOSTS =
      Set.of("music.youtube.com", "www.youtube.com", "youtube.com", "m.youtube.com", "youtu.be");

  private final YtDlpTrackSource youtubeMusic;
  private final MusicDownloader downloader;
  private final ActivityLog activity;
  private final TaskExecutor executor;
  private final Clock clock;
  private final Path libraryRoot;
  private final int maxTracks;

  /** Null while nothing has been imported; otherwise the state of the last or running import. */
  private final AtomicReference<ImportProgress> progress = new AtomicReference<>();

  public PlaylistImportService(
      @Value("${songradar.storage.library}") Path libraryRoot,
      @Value("${songradar.library.import.max-tracks:100}") int maxTracks,
      YtDlpTrackSource youtubeMusic,
      MusicDownloader downloader,
      @Qualifier("importTaskExecutor") TaskExecutor executor,
      ActivityLog activity,
      Clock clock) {
    this.libraryRoot = libraryRoot.toAbsolutePath().normalize();
    this.maxTracks = Math.max(1, maxTracks);
    this.youtubeMusic = youtubeMusic;
    this.downloader = downloader;
    this.executor = executor;
    this.activity = activity;
    this.clock = clock;
  }

  /**
   * Returns as soon as the link is accepted. An import is minutes of downloading — a request thread
   * cannot hold it open, and the activity feed is where it reports.
   */
  public ImportRequest start(String url) {
    String link = url == null ? "" : url.trim();
    if (!isYouTubeMusicLink(link)) {
      return ImportRequest.INVALID_LINK;
    }
    ImportProgress running = progress.get();
    if (running != null && running.running()) {
      return ImportRequest.ALREADY_RUNNING;
    }
    ImportProgress queued = ImportProgress.starting(link, clock.instant());
    if (!progress.compareAndSet(running, queued)) {
      return ImportRequest.ALREADY_RUNNING;
    }
    executor.execute(() -> run(link));
    return ImportRequest.STARTED;
  }

  /** Empty until something has been imported, so the page can leave the panel out. */
  public Optional<ImportProgress> progress() {
    return Optional.ofNullable(progress.get());
  }

  public int maxTracks() {
    return maxTracks;
  }

  private void run(String url) {
    Path folder = libraryRoot.resolve(FOLDER);
    List<YtDlpTrackSource.ImportedTrack> tracks;
    try {
      tracks = youtubeMusic.tracksAt(url, maxTracks);
    } catch (IOException | RuntimeException exception) {
      log.info("Could not read the YouTube Music link {}: {}", url, exception.toString());
      finish(url, 0, 0, 0, "Could not read that link: " + reason(exception));
      return;
    }
    if (tracks.isEmpty()) {
      finish(url, 0, 0, 0, "That link holds no tracks.");
      return;
    }
    progress.updateAndGet(current -> current.withTotal(tracks.size()));
    activity.record(
        ActivityKind.SEARCHING,
        FOLDER,
        null,
        "YouTube Music",
        "Importing "
            + tracks.size()
            + (tracks.size() == 1 ? " track" : " tracks")
            + " from a link");

    int saved = 0;
    int done = 0;
    for (YtDlpTrackSource.ImportedTrack track : tracks) {
      if (store(track, folder)) {
        saved++;
      }
      done++;
      int completed = done;
      int stored = saved;
      progress.updateAndGet(current -> current.withProgress(completed, stored));
    }
    finish(
        url,
        tracks.size(),
        tracks.size(),
        saved,
        saved == tracks.size()
            ? "Imported every track."
            : "Imported " + saved + " of " + tracks.size() + " tracks.");
  }

  private boolean store(YtDlpTrackSource.ImportedTrack track, Path folder) {
    try {
      if (downloader.download(asDetection(track), folder)) {
        return true;
      }
      // Nothing matched by name, so take the recording the link actually pointed at.
      return youtubeMusic
          .trackById(track.id())
          .map(exact -> downloader.save(exact, folder, track.artist(), track.title()))
          .orElse(false);
    } catch (RuntimeException exception) {
      log.info("Could not import \"{}\": {}", track.title(), exception.toString());
      return false;
    }
  }

  private SoundDetection asDetection(YtDlpTrackSource.ImportedTrack track) {
    Instant now = clock.instant();
    return new SoundDetection(
        null,
        null,
        DetectionStatus.MATCHED,
        "YouTube Music",
        null,
        track.title(),
        track.artist(),
        null,
        null,
        "https://music.youtube.com/watch?v=" + track.id(),
        now,
        now,
        1,
        null,
        FOLDER);
  }

  private void finish(String url, int total, int done, int saved, String message) {
    progress.set(new ImportProgress(false, url, total, done, saved, message, clock.instant()));
    activity.record(
        ActivityKind.SAVED, FOLDER, null, "YouTube Music", "Import finished — " + message);
  }

  /**
   * Only YouTube's own hosts: the link is handed to a program that will fetch whatever it is given,
   * and an import is started by anyone who can reach the page.
   */
  private static boolean isYouTubeMusicLink(String url) {
    if (url.isBlank() || url.length() > 2000) {
      return false;
    }
    try {
      URI parsed = new URI(url);
      String scheme = parsed.getScheme();
      String host = parsed.getHost();
      return host != null
          && scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && HOSTS.contains(host.toLowerCase(Locale.ROOT));
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  private static String reason(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return exception.getClass().getSimpleName();
    }
    return message.length() <= 200 ? message : message.substring(0, 200);
  }

  public enum ImportRequest {
    STARTED,
    INVALID_LINK,
    ALREADY_RUNNING,
  }

  /** What the page shows while an import runs, and what it says once one has finished. */
  public record ImportProgress(
      boolean running, String url, int total, int done, int saved, String message, Instant at) {

    static ImportProgress starting(String url, Instant at) {
      return new ImportProgress(true, url, 0, 0, 0, "Reading the link…", at);
    }

    ImportProgress withTotal(int total) {
      return new ImportProgress(true, url, total, done, saved, "Downloading…", at);
    }

    ImportProgress withProgress(int done, int saved) {
      return new ImportProgress(true, url, total, done, saved, "Downloading…", at);
    }
  }
}
