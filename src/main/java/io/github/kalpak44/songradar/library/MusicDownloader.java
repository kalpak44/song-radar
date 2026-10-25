package io.github.kalpak44.songradar.library;

import io.github.kalpak44.songradar.detection.SoundDetection;
import java.nio.file.Path;

/** Downloads a detected track to the supplied library directory. */
public interface MusicDownloader {

  void download(SoundDetection detection, Path destinationFolder);
}
