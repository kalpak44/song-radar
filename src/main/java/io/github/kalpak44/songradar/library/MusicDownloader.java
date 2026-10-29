package io.github.kalpak44.songradar.library;

import io.github.kalpak44.songradar.detection.SoundDetection;
import java.nio.file.Path;

/** Downloads a detected track to the supplied library directory. */
public interface MusicDownloader {

  /** True when a file was stored, so a caller importing a list can say how far it got. */
  boolean download(SoundDetection detection, Path destinationFolder);

  /**
   * Stores a track that is already in hand, with the same length checks and tagging a searched one
   * gets. An import uses this when it knows exactly which recording it wants and searching the
   * catalogues by name could only find a different one.
   */
  boolean save(SourceTrack track, Path destinationFolder, String artist, String title);
}
