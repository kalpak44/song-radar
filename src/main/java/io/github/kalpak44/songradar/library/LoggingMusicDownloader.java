package io.github.kalpak44.songradar.library;

import io.github.kalpak44.songradar.detection.SoundDetection;
import java.nio.file.Path;
import java.text.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Placeholder downloader: logs the intended MP3 path without creating or downloading a file. */
@Component
public class LoggingMusicDownloader implements MusicDownloader {

  private static final Logger log = LoggerFactory.getLogger(LoggingMusicDownloader.class);

  @Override
  public void download(SoundDetection detection, Path destinationFolder) {
    String artist = cleanPart(detection.artist());
    String title = cleanPart(detection.title());
    String preferredName =
        artist.isBlank() ? title : title.isBlank() ? artist : artist + " - " + title;
    if (preferredName.isBlank()) {
      preferredName = "track-" + detection.id();
    }
    Path target = destinationFolder.resolve(preferredName + ".mp3").normalize();
    log.info("Dummy downloader: would save detected track as {}", target);
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
