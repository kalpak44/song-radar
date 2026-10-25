package io.github.kalpak44.songradar.library;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores a catalogue result against the detected track. A search for a popular song answers with
 * karaoke versions, remixes and DJ sets whose titles contain every word of it, so word overlap
 * alone would pick one of those as readily as the recording itself.
 */
final class TrackMatching {

  /**
   * Titles that answer a search for a song without being it. Phrases are matched as written; single
   * words are matched whole, or "live" would reject Oliver and "edit" would reject credits.
   */
  private static final List<String> IMPOSTERS =
      List.of(
          "karaoke",
          "instrumental",
          "backing track",
          "tribute",
          "made famous by",
          "cover of",
          "remix",
          "rework",
          "remake",
          "bootleg",
          "mashup",
          "nightcore",
          "edit",
          "live",
          "acoustic",
          "sped up",
          "slowed");

  /** Below this, the best result is treated as the wrong song rather than a weak match. */
  static final double MINIMUM_SCORE = 0.6;

  /** Enough to put a full word overlap under the threshold on its own. */
  private static final double IMPOSTER_PENALTY = 0.5;

  private TrackMatching() {}

  static double score(String candidate, String artist, String title) {
    String haystack = normalise(candidate);
    // Distinct, or a title like "Sing Sing Sing" scores three fifths for a candidate that
    // contains the word once and nothing else of the artist or the song.
    List<String> wanted =
        Arrays.stream(normalise(artist + " " + title).split(" "))
            .filter(w -> !w.isBlank())
            .distinct()
            .toList();
    if (wanted.isEmpty() || haystack.isEmpty()) {
      return 0;
    }
    long matched = wanted.stream().filter(haystack::contains).count();
    double ratio = (double) matched / wanted.size();
    String wantedTitle = normalise(title);
    return imposter(haystack, wantedTitle) ? ratio - IMPOSTER_PENALTY : ratio;
  }

  /** A marker the detected title does not itself carry means this is a different recording. */
  private static boolean imposter(String haystack, String wantedTitle) {
    Set<String> words = new LinkedHashSet<>(Arrays.asList(haystack.split(" ")));
    Set<String> wantedWords = new LinkedHashSet<>(Arrays.asList(wantedTitle.split(" ")));
    for (String marker : IMPOSTERS) {
      boolean present = marker.contains(" ") ? haystack.contains(marker) : words.contains(marker);
      boolean wanted =
          marker.contains(" ") ? wantedTitle.contains(marker) : wantedWords.contains(marker);
      if (present && !wanted) {
        return true;
      }
    }
    return false;
  }

  static String normalise(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }
}
