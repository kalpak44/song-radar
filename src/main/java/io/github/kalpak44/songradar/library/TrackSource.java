package io.github.kalpak44.songradar.library;

import java.util.Optional;

/**
 * One catalogue a detected track can be fetched from. A source that cannot find the track, or
 * cannot serve audio for it, returns empty so the next source gets a turn.
 */
public interface TrackSource {

  /** Named in the log line that reports which source a file came from. */
  String name();

  Optional<SourceTrack> find(String artist, String title);
}
