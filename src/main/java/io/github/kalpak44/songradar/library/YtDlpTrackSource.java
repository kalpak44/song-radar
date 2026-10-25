package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Last in the chain, and the only source that runs another program. Nothing else here reaches
 * YouTube: its own endpoints answer LOGIN_REQUIRED to every client, and the public mirrors are
 * gone, so the alternative to this process was no mainstream catalogue at all.
 *
 * <p>It is last because it is the slowest and the most fragile — YouTube changes what it serves and
 * a yt-dlp that is months old downloads metadata happily and then fails on the media with a 403.
 * The scheduled source check is what reports that; see docs/music-sources.md.
 */
@Component
@Order(9)
public class YtDlpTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(YtDlpTrackSource.class);

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /** Enough for a slow extraction, short enough that a hung process is not a stuck download. */
  private static final int SEARCH_TIMEOUT_SECONDS = 60;

  /** The extraction directory holds downloaded audio, so only this process may read it. */
  private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_DIRECTORY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  /** A missing binary is a deployment state, not a per-track event, so it is said once. */
  private volatile boolean reportedMissing;

  private final String executable;
  private final String ffmpegLocation;
  private final int downloadTimeoutSeconds;

  /**
   * Hyphen-free property names so the container can set them as SONGRADAR_LIBRARY_YTDLP_PATH and
   * SONGRADAR_LIBRARY_YTDLP_FFMPEG.
   */
  public YtDlpTrackSource(
      @Value("${songradar.library.ytdlp.path:yt-dlp}") String executable,
      @Value("${songradar.library.ytdlp.ffmpeg:}") String ffmpegLocation,
      @Value("${songradar.library.ytdlp.timeout-seconds:300}") int downloadTimeoutSeconds) {
    this.executable = executable;
    this.ffmpegLocation = ffmpegLocation == null ? "" : ffmpegLocation.trim();
    this.downloadTimeoutSeconds = downloadTimeoutSeconds;
  }

  @Override
  public String name() {
    return "YouTube";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      // Flat results: a full extraction per candidate costs a request each and everything
      // scored here is in the flat entry already.
      String output =
          run(
              SEARCH_TIMEOUT_SECONDS,
              executable,
              "--no-warnings",
              "--flat-playlist",
              "--dump-json",
              "ytsearch5:" + (artist + " " + title).trim());
      List<Entry> entries = new ArrayList<>();
      for (String line : output.split("\\R")) {
        if (!line.isBlank()) {
          entries.add(JSON.readValue(line, Entry.class));
        }
      }
      return entries.stream()
          .filter(entry -> entry.id() != null)
          .max(Comparator.comparingDouble(entry -> score(entry, artist, title)))
          .filter(entry -> score(entry, artist, title) >= TrackMatching.MINIMUM_SCORE)
          .map(entry -> toSourceTrack(entry, artist));
    } catch (IOException | RuntimeException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("Cannot run program")) {
        if (!reportedMissing) {
          reportedMissing = true;
          log.info("yt-dlp is not installed at \"{}\"; skipping this source", executable);
        }
      } else {
        log.info("yt-dlp could not serve \"{} - {}\": {}", artist, title, exception.toString());
      }
      return Optional.empty();
    }
  }

  private SourceTrack toSourceTrack(Entry entry, String artist) {
    return new SourceTrack(
        name(),
        entry.title(),
        entry.channel() == null ? artist : entry.channel(),
        Math.round(entry.duration() * 1000),
        out -> download(entry.id(), out));
  }

  /**
   * yt-dlp writes the MP3 itself. Its own extraction is used rather than this project's because the
   * M4A stream YouTube serves needs a container fixup before anything can decode it, and that fixup
   * is ffmpeg — which has to be installed beside yt-dlp either way.
   */
  private void download(String id, OutputStream out) throws IOException {
    Path work = Files.createTempDirectory("songradar-ytdlp-", PRIVATE_DIRECTORY);
    try {
      List<String> command =
          new ArrayList<>(
              List.of(
                  executable,
                  "--no-warnings",
                  "--no-playlist",
                  "--extract-audio",
                  "--audio-format",
                  "mp3",
                  "--audio-quality",
                  "0",
                  "-o",
                  work.resolve("track.%(ext)s").toString()));
      // Named rather than left to PATH: the container installs it beside yt-dlp rather than
      // into a directory the process already searches.
      if (!ffmpegLocation.isEmpty()) {
        command.add("--ffmpeg-location");
        command.add(ffmpegLocation);
      }
      command.add("https://www.youtube.com/watch?v=" + id);
      run(downloadTimeoutSeconds, command.toArray(new String[0]));
      Path audio = work.resolve("track.mp3");
      if (!Files.isRegularFile(audio)) {
        throw new IOException("yt-dlp produced no audio file");
      }
      Files.copy(audio, out);
    } finally {
      try (var paths = Files.walk(work)) {
        paths.sorted(Comparator.reverseOrder()).forEach(YtDlpTrackSource::deleteQuietly);
      }
    }
  }

  /**
   * Arguments are passed as a list, never through a shell: the query is a track title that came
   * from a recognition provider, and quoting it into a command line would be an injection.
   */
  private String run(int timeoutSeconds, String... command) throws IOException {
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(false)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start();
    try {
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("yt-dlp did not finish within " + timeoutSeconds + "s");
      }
      if (process.exitValue() != 0) {
        String error =
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        throw new IOException(
            "yt-dlp exited " + process.exitValue() + (error.isEmpty() ? "" : ": " + error));
      }
      return output;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("interrupted while waiting for yt-dlp", exception);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      log.debug("Could not remove {}", path, exception);
    }
  }

  private static double score(Entry entry, String artist, String title) {
    String channel = entry.channel() == null ? "" : entry.channel();
    return TrackMatching.score(entry.title() + " " + channel, artist, title);
  }

  record Entry(String id, String title, String channel, double duration) {}
}
