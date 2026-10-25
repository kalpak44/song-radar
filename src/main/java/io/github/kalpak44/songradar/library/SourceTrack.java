package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A match in a source catalogue. The audio is not fetched until {@link Mp3Content#writeTo} runs, so
 * scoring candidates costs one search request rather than one download each.
 */
public record SourceTrack(
    String source, String title, String artist, long durationMillis, Mp3Content content) {

  /** Writes the track as MP3, transcoding on the way if the source does not serve MP3. */
  @FunctionalInterface
  public interface Mp3Content {
    void writeTo(OutputStream out) throws IOException;
  }
}
