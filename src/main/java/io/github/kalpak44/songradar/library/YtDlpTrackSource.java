package io.github.kalpak44.songradar.library;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
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
 * The only source that runs another program. Nothing else here reaches YouTube: its own endpoints
 * answer LOGIN_REQUIRED to every client, and the public mirrors are gone, so the alternative to
 * this process was no mainstream catalogue at all.
 *
 * <p>It searches YouTube Music rather than YouTube. Both are served by the same yt-dlp and the same
 * media, but a YouTube Music result is a song with an artist and an album attached, while a YouTube
 * result is whatever a channel uploaded under that name — and the artist is the half of the match
 * that decides whether this is the right recording; see docs/music-sources.md.
 *
 * <p>Third on audio alone: the ~160 kbps Opus it serves is worth more than the 128 kbps MP3 the two
 * sources below it hand over, and yt-dlp re-encodes it with ffmpeg at LAME V0 rather than through
 * this project's transcoder. That ranking costs time on every miss above it — a search and five
 * extractions run before the chain moves on — and YouTube refuses datacenter addresses, so a
 * deployment that is blocked pays that before reaching SoundCloud. Moving this back to the end of
 * the chain is a one-line change to the order below.
 *
 * <p>It is also the most fragile: YouTube changes what it serves and a yt-dlp that is months old
 * downloads metadata happily and then fails on the media with a 403. The scheduled source check is
 * what reports that.
 */
@Component
@Order(3)
public class YtDlpTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(YtDlpTrackSource.class);

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  /** Enough for a slow extraction, short enough that a hung process is not a stuck download. */
  private static final int SEARCH_TIMEOUT_SECONDS = 60;

  /**
   * YouTube Music's own "Songs" search filter, which is what keeps album, artist and playlist rows
   * out of the result. It is an opaque blob and may stop being honoured; the search then answers
   * with the mixed listing it serves by default, which is why only watch entries are kept below.
   */
  private static final String SONGS_ONLY = "EgWKAQIIAWoKEAoQCRADEAQQBQ%3D%3D";

  /** How many results are described. Measured at roughly a second and a half each. */
  private static final int CANDIDATES = 5;

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
    return "YouTube Music";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      List<Entry> candidates = describe(search(artist, title));
      return candidates.stream()
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
        log.info(
            "YouTube Music could not serve \"{} - {}\": {}", artist, title, exception.toString());
      }
      return Optional.empty();
    }
  }

  /**
   * The tracks a YouTube Music link stands for: one for a track, the whole listing for a playlist.
   *
   * <p>A link is resolved in two passes because the two shapes answer differently. A bare track URL
   * extracts in full and already carries the artist and the song title. A playlist URL is listed
   * flat — an id and whatever YouTube shows in the row, which for a song is the uploading channel
   * rather than the performer — so those are extracted afterwards for the real names.
   *
   * <p>{@code limit} is not a formality. A YouTube Music link that carries a {@code RDAMVM} list is
   * an endless radio mix rather than a playlist: the one this was written against resolved to 2,197
   * entries, which is a download measured in days.
   */
  public List<ImportedTrack> tracksAt(String url, int limit) throws IOException {
    List<Entry> listed =
        parse(
            run(
                    SEARCH_TIMEOUT_SECONDS,
                    executable,
                    "--no-warnings",
                    "--flat-playlist",
                    "--playlist-end",
                    String.valueOf(Math.max(1, limit)),
                    "--dump-json",
                    url)
                .succeededOrThrow());
    List<Entry> songs =
        listed.stream().filter(entry -> entry.id() != null && !entry.isContainer()).toList();
    if (songs.isEmpty()) {
      return List.of();
    }
    // Already extracted in full when the link was a single track; describing it again would be a
    // second round trip for metadata that is in hand.
    List<Entry> described =
        songs.size() == 1 && songs.getFirst().artist() != null
            ? songs
            : describe(songs.stream().map(Entry::id).toList());
    return described.stream()
        .filter(entry -> entry.id() != null && entry.heading() != null)
        .map(entry -> new ImportedTrack(entry.id(), entry.heading(), entry.primaryArtist()))
        .toList();
  }

  /**
   * The track behind one YouTube Music id, with no searching and so no chance of a near miss. The
   * importer falls back to this when the catalogues cannot place a track by name.
   */
  public Optional<SourceTrack> trackById(String id) {
    try {
      return describe(List.of(id)).stream()
          .filter(entry -> entry.id() != null)
          .findFirst()
          .map(entry -> toSourceTrack(entry, ""));
    } catch (IOException | RuntimeException exception) {
      log.info("YouTube Music could not describe {}: {}", id, exception.toString());
      return Optional.empty();
    }
  }

  /** One track of an imported link, named as YouTube Music names it. */
  public record ImportedTrack(String id, String title, String artist) {}

  /**
   * Flat, because the search listing carries nothing that is scored here — a YouTube Music result
   * is a title and an id, and the artist and the length arrive only with the extraction below.
   */
  private List<String> search(String artist, String title) throws IOException {
    String query = URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
    Run search =
        run(
            SEARCH_TIMEOUT_SECONDS,
            executable,
            "--no-warnings",
            "--flat-playlist",
            "--playlist-end",
            String.valueOf(CANDIDATES),
            "--dump-json",
            "https://music.youtube.com/search?q=" + query + "&sp=" + SONGS_ONLY);
    List<String> ids = new ArrayList<>();
    for (Entry entry : parse(search.succeededOrThrow())) {
      // An artist or album row extracts to a whole channel rather than a song, and the songs
      // filter is the only thing keeping them out of this listing.
      if (entry.id() != null && "Youtube".equals(entry.ieKey())) {
        ids.add(entry.id());
      }
    }
    return ids;
  }

  /**
   * One process for every candidate: each is a separate extraction either way, and starting the
   * binary once is what keeps describing five results to about seven seconds. A track that has gone
   * private fails its own extraction and yt-dlp still exits non-zero, so the output is read rather
   * than the exit code — the remaining candidates are the point of asking for five.
   */
  private List<Entry> describe(List<String> ids) throws IOException {
    if (ids.isEmpty()) {
      return List.of();
    }
    List<String> command =
        new ArrayList<>(
            List.of(
                executable, "--no-warnings", "--no-playlist", "--skip-download", "--dump-json"));
    ids.forEach(id -> command.add(watchUrl(id)));
    Run described = run(SEARCH_TIMEOUT_SECONDS, command.toArray(new String[0]));
    return parse(described.output().isBlank() ? described.succeededOrThrow() : described.output());
  }

  private static List<Entry> parse(String output) {
    List<Entry> entries = new ArrayList<>();
    for (String line : output.split("\\R")) {
      if (!line.isBlank()) {
        entries.add(JSON.readValue(line, Entry.class));
      }
    }
    return entries;
  }

  private SourceTrack toSourceTrack(Entry entry, String artist) {
    return new SourceTrack(
        name(),
        entry.heading(),
        entry.performer() == null ? artist : entry.performer(),
        entry.durationMillis(),
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
      command.add(watchUrl(id));
      run(downloadTimeoutSeconds, command.toArray(new String[0])).succeededOrThrow();
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

  /** The media is the same on either host; this one is where the search found it. */
  private static String watchUrl(String id) {
    return "https://music.youtube.com/watch?v=" + id;
  }

  /**
   * Arguments are passed as a list, never through a shell: the query is a track title that came
   * from a recognition provider, and quoting it into a command line would be an injection.
   */
  private Run run(int timeoutSeconds, String... command) throws IOException {
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
      String error =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return new Run(process.exitValue(), output, error);
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
    String performer = entry.performer() == null ? "" : entry.performer();
    return TrackMatching.score(entry.heading() + " " + performer, artist, title);
  }

  /** What a finished yt-dlp left behind, so a partly failed run can still be read. */
  private record Run(int exitCode, String output, String error) {

    String succeededOrThrow() throws IOException {
      if (exitCode != 0) {
        throw new IOException("yt-dlp exited " + exitCode + (error.isEmpty() ? "" : ": " + error));
      }
      return output;
    }
  }

  /**
   * A flat search result carries only the id and the title, and leaves everything else null; the
   * rest arrives from the extraction. {@code track} and {@code artist} are what YouTube Music knows
   * about the song, where {@code title} and {@code channel} are what YouTube would have said.
   */
  record Entry(
      String id,
      String title,
      String track,
      String artist,
      List<String> artists,
      String channel,
      Double duration,
      @JsonProperty("ie_key") String ieKey) {

    /** A YouTube Music channel for a song is named for the artist with this appended. */
    private static final String TOPIC = " - Topic";

    String heading() {
      return track == null ? title : track;
    }

    String performer() {
      return artist == null ? channel : artist;
    }

    /**
     * The performer alone. YouTube Music lists the songwriters alongside them, and {@code artist}
     * is all of those joined — "ЗОМБ, Трегубов Семён Александрович" for a track credited to ЗОМБ.
     * That is a filename nobody wants and, worse, three extra words the matcher looks for in every
     * candidate, which is enough to put a correct one under the threshold and lose the track to a
     * catalogue that has it.
     */
    String primaryArtist() {
      if (artists != null && !artists.isEmpty() && !artists.getFirst().isBlank()) {
        return artists.getFirst().trim();
      }
      if (artist != null && !artist.isBlank()) {
        return artist.trim();
      }
      if (channel == null) {
        return null;
      }
      return channel.endsWith(TOPIC)
          ? channel.substring(0, channel.length() - TOPIC.length()).trim()
          : channel;
    }

    long durationMillis() {
      return duration == null ? 0 : Math.round(duration * 1000);
    }

    /** An artist or album row, which extracts to a whole channel rather than to a song. */
    boolean isContainer() {
      return ieKey != null && !"Youtube".equals(ieKey);
    }
  }
}
