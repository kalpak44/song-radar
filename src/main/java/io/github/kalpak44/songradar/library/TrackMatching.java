package io.github.kalpak44.songradar.library;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Scores a catalogue result against the detected track. A search for a popular song answers with
 * karaoke and tribute uploads whose titles contain every word of it, so word overlap alone would
 * pick one of those as readily as the recording itself.
 */
final class TrackMatching {

  /** Uploads that answer a search for a song without being it. */
  private static final List<String> IMPOSTERS =
      List.of("karaoke", "instrumental", "backing track", "tribute", "made famous by", "cover of");

  /** Below this, the best result is treated as the wrong song rather than a weak match. */
  static final double MINIMUM_SCORE = 0.6;

  private TrackMatching() {}

  static double score(String candidate, String artist, String title) {
    String haystack = normalise(candidate);
    List<String> wanted =
        Arrays.stream(normalise(artist + " " + title).split(" "))
            .filter(w -> !w.isBlank())
            .toList();
    if (wanted.isEmpty() || haystack.isEmpty()) {
      return 0;
    }
    long matched = wanted.stream().filter(haystack::contains).count();
    double ratio = (double) matched / wanted.size();
    String wantedTitle = normalise(title);
    boolean imposter =
        IMPOSTERS.stream().anyMatch(word -> haystack.contains(word) && !wantedTitle.contains(word));
    return imposter ? ratio - 0.5 : ratio;
  }

  static String normalise(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }
}
