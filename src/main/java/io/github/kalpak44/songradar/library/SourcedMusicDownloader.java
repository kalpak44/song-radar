package io.github.kalpak44.songradar.library;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;
import io.github.kalpak44.songradar.activity.ActivityKind;
import io.github.kalpak44.songradar.activity.ActivityLog;
import io.github.kalpak44.songradar.detection.SoundDetection;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fetches a detected track from the first source that has it. A source that cannot find the track,
 * or fails part-way through serving it, hands over to the next rather than failing the download.
 */
@Component
public class SourcedMusicDownloader implements MusicDownloader {

  private static final Logger log = LoggerFactory.getLogger(SourcedMusicDownloader.class);

  /** Kept in step with the prefix the file manager filters out of its listings. */
  private static final String HIDDEN_PREFIX = ".songradar-download-";

  /**
   * A source that answers with an error page, or a stream that stops after a few segments, still
   * produces a file. Nothing shorter than this is a track, so it counts as a failed download and
   * the next source gets its turn.
   */
  private static final int SHORTEST_TRACK_SECONDS = 30;

  /**
   * A transfer that stops early still produces a parseable file, and the duration it reports comes
   * from a header describing the whole track rather than the bytes that arrived — so a truncated
   * download passes the length check. Its size does not lie.
   */
  private static final long SMALLEST_TRACK_BYTES = 2_500_000;

  /**
   * How much of the length a source promised has to actually arrive. Catalogues stream a preview
   * clip for a track they will not give away, and it downloads cleanly — the only thing that says
   * so is the gap between the duration advertised in the search result and the audio delivered.
   */
  private static final double SHORTEST_ACCEPTABLE_SHARE = 0.8;

  private final List<TrackSource> sources;
  private final ActivityLog activity;

  @Autowired
  public SourcedMusicDownloader(List<TrackSource> sources, ActivityLog activity) {
    this.sources = List.copyOf(sources);
    this.activity = activity;
  }

  /** For tests that are not about the activity feed. */
  public SourcedMusicDownloader(List<TrackSource> sources) {
    this(sources, ActivityLog.NONE);
  }

  @Override
  public void download(SoundDetection detection, Path destinationFolder) {
    String artist = cleanPart(detection.artist());
    String title = cleanPart(detection.title());
    if (artist.isBlank() && title.isBlank()) {
      log.info("Detection {} has no artist or title to search for", detection.id());
      return;
    }
    Path target = destinationFolder.resolve(fileName(detection, artist, title)).normalize();
    // The folder is named for the listener that recorded the track, which is the only place
    // the name is available here and the only place the feed needs it.
    String listener = String.valueOf(destinationFolder.getFileName());
    String track = artist.isBlank() ? title : artist + " - " + title;

    for (TrackSource source : sources) {
      Optional<SourceTrack> found = source.find(artist, title);
      if (found.isEmpty()) {
        activity.record(
            ActivityKind.SEARCHING, listener, track, source.name(), "No match in this catalogue");
        continue;
      }
      try {
        store(found.get(), target, artist, title);
        log.info("Saved \"{} - {}\" from {} to {}", artist, title, found.get().source(), target);
        activity.record(
            ActivityKind.SAVED,
            listener,
            track,
            found.get().source(),
            "Saved as " + target.getFileName());
        return;
      } catch (IOException | RuntimeException exception) {
        log.info(
            "{} matched \"{} - {}\" but could not deliver it: {}",
            source.name(),
            artist,
            title,
            exception.toString());
        activity.record(
            ActivityKind.SEARCHING,
            listener,
            track,
            source.name(),
            "Matched but could not deliver it: " + reason(exception));
      }
    }
    log.info("No source had \"{} - {}\"", artist, title);
    activity.record(
        ActivityKind.UNAVAILABLE, listener, track, null, "No source could deliver this track");
  }

  /**
   * Written beside the target and moved over it, so a failed or half-written download never
   * replaces a file that was already playable. The name carries the prefix the file manager hides:
   * a download in progress would otherwise be listed as a playable track that stops existing the
   * moment it finishes.
   */
  private void store(SourceTrack track, Path target, String artist, String title)
      throws IOException {
    Files.createDirectories(target.getParent());
    Path partial = Files.createTempFile(target.getParent(), HIDDEN_PREFIX, ".mp3");
    try {
      try (OutputStream out = Files.newOutputStream(partial)) {
        track.content().writeTo(out);
      }
      tag(playable(partial, track.durationMillis()), partial, track.source(), artist, title);
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /**
   * Parses what arrived rather than trusting the transfer, so only real audio is ever filed. The
   * length check is the half that catches a stream which started correctly and then stopped.
   */
  private static Mp3File playable(Path file, long expectedMillis) throws IOException {
    long size = Files.size(file);
    if (size < SMALLEST_TRACK_BYTES) {
      throw new IOException("the download stopped after " + size / 1024 + " KB");
    }
    Mp3File mp3;
    try {
      mp3 = new Mp3File(file);
    } catch (Exception exception) {
      throw new IOException("the source did not return playable audio", exception);
    }
    if (mp3.getLengthInSeconds() < SHORTEST_TRACK_SECONDS) {
      throw new IOException(
          "the source returned only " + mp3.getLengthInSeconds() + " seconds of audio");
    }
    long expectedSeconds = expectedMillis / 1000;
    if (expectedSeconds > 0
        && mp3.getLengthInSeconds() < expectedSeconds * SHORTEST_ACCEPTABLE_SHARE) {
      throw new IOException(
          "the source served "
              + mp3.getLengthInSeconds()
              + "s of a "
              + expectedSeconds
              + "s track, which is a preview rather than the track");
    }
    return mp3;
  }

  /**
   * Tagged with the detected artist and title rather than the upload's own, which carries the
   * uploader's account name and decorations like "[Original Version]" into every player. Untagged
   * audio still plays, so a tagging failure is reported rather than raised.
   */
  private void tag(Mp3File mp3, Path file, String source, String artist, String title) {
    try {
      ID3v24Tag tags = new ID3v24Tag();
      tags.setArtist(artist);
      tags.setTitle(title);
      tags.setComment("Downloaded by SongRadar from " + source);
      mp3.setId3v2Tag(tags);
      Path tagged = file.resolveSibling(file.getFileName() + ".tagged");
      mp3.save(tagged.toString());
      Files.move(tagged, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception exception) {
      log.info("Could not tag {}: {}", file.getFileName(), exception.toString());
    }
  }

  /** The exception text alone, since the feed has no room for a stack trace. */
  private static String reason(Exception exception) {
    return exception.getMessage() == null
        ? exception.getClass().getSimpleName()
        : exception.getMessage();
  }

  private static String fileName(SoundDetection detection, String artist, String title) {
    String preferred = artist.isBlank() ? title : title.isBlank() ? artist : artist + " - " + title;
    return (preferred.isBlank() ? "track-" + detection.id() : preferred) + ".mp3";
  }

  private static String cleanPart(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
    String safe =
        normalized.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
    if (safe.length() > 120) {
      safe = safe.substring(0, 120).trim();
    }
    return safe.equals(".") || safe.equals("..") ? "" : safe;
  }
}
