package io.github.kalpak44.songradar.library;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;
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
import org.springframework.stereotype.Component;

/**
 * Fetches a detected track from the first source that has it. A source that cannot find the track,
 * or fails part-way through serving it, hands over to the next rather than failing the download.
 */
@Component
public class SourcedMusicDownloader implements MusicDownloader {

  private static final Logger log = LoggerFactory.getLogger(SourcedMusicDownloader.class);

  private final List<TrackSource> sources;

  public SourcedMusicDownloader(List<TrackSource> sources) {
    this.sources = List.copyOf(sources);
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

    for (TrackSource source : sources) {
      Optional<SourceTrack> found = source.find(artist, title);
      if (found.isEmpty()) {
        continue;
      }
      try {
        store(found.get(), target, artist, title);
        log.info("Saved \"{} - {}\" from {} to {}", artist, title, found.get().source(), target);
        return;
      } catch (IOException | RuntimeException exception) {
        log.info(
            "{} matched \"{} - {}\" but could not deliver it: {}",
            source.name(),
            artist,
            title,
            exception.toString());
      }
    }
    log.info("No source had \"{} - {}\"", artist, title);
  }

  /**
   * Written beside the target and moved over it, so a failed or half-written download never
   * replaces a file that was already playable.
   */
  private void store(SourceTrack track, Path target, String artist, String title)
      throws IOException {
    Files.createDirectories(target.getParent());
    Path partial = Files.createTempFile(target.getParent(), ".download-", ".mp3");
    try {
      try (OutputStream out = Files.newOutputStream(partial)) {
        track.content().writeTo(out);
      }
      if (Files.size(partial) == 0) {
        throw new IOException("the source produced an empty file");
      }
      tag(partial, track.source(), artist, title);
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /**
   * Tagged with the detected artist and title rather than the upload's own, which carries the
   * uploader's account name and decorations like "[Original Version]" into every player. Untagged
   * audio still plays, so a tagging failure is reported rather than raised.
   */
  private void tag(Path file, String source, String artist, String title) {
    try {
      Mp3File mp3 = new Mp3File(file);
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
