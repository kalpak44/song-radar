package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Downloads a real track from every source. These catalogues are third-party APIs that change
 * without notice, and nothing else here can tell a working source from one that has started
 * answering differently — so this runs on a schedule rather than in the build, and a failure is the
 * signal that a source needs repairing. See docs/music-sources.md.
 */
@Tag("live")
class SourceAvailabilityTest {

  /**
   * Catalogues differ, so each source is asked for something it should plausibly hold. Two queries
   * because one track being pulled from a catalogue is not the same as the source being broken.
   */
  private static final List<Probe> PROBES =
      List.of(
          new Probe(
              new AudiusTrackSource(),
              List.of(new String[] {"deadmau5", "Unlucky"}, new String[] {"Mr Carmack", "Blow"})),
          new Probe(
              new SoundCloudTrackSource(),
              List.of(
                  // Served as an MPEG playlist rather than AAC, which is a separate path.
                  new String[] {"Jonas Blue", "We Could Go Back"},
                  new String[] {"Кино", "Группа крови"})),
          new Probe(
              new JioSaavnTrackSource(),
              List.of(
                  new String[] {"Arijit Singh", "Tum Hi Ho"},
                  new String[] {"Neha Kakkar", "Dilbar"})),
          new Probe(
              new BandcampTrackSource(),
              List.of(
                  new String[] {"Amanda Palmer", "In My Mind"},
                  new String[] {"Car Seat Headrest", "Something Soon"})),
          new Probe(
              new YtDlpTrackSource("yt-dlp", "", 300),
              List.of(
                  new String[] {"Jonas Blue", "We Could Go Back"},
                  new String[] {"Daft Punk", "Get Lucky"}),
              false));

  /**
   * A non-ASCII query is encoded differently by every one of these clients, and getting it wrong
   * does not fail — it answers with unrelated tracks, which the matcher then rejects as if the
   * catalogue simply had nothing.
   */
  private static final String[] CYRILLIC = {"Кино", "Группа крови"};

  /**
   * {@code required} is false for a source that can fail for reasons this repository cannot fix —
   * YouTube refuses datacenter addresses, so a runner is told to sign in. Its result is reported
   * but does not fail the check, or the check would be red for ever and stop being read.
   */
  record Probe(TrackSource source, List<String[]> queries, boolean required) {
    Probe(TrackSource source, List<String[]> queries) {
      this(source, queries, true);
    }
  }

  @TempDir Path library;

  @Test
  void everySourceStillServesADownloadableTrack() {
    List<String> broken = new ArrayList<>();
    List<String> working = new ArrayList<>();

    for (Probe probe : PROBES) {
      String outcome = attempt(probe);
      if (outcome == null) {
        working.add(probe.source().name());
        System.out.printf("%-12s OK%n", probe.source().name());
      } else {
        if (probe.required()) {
          broken.add(probe.source().name());
        }
        System.out.printf(
            "%-12s %s — %s%n",
            probe.source().name(), probe.required() ? "BROKEN" : "unavailable", outcome);
      }
    }

    System.out.printf("%d of %d sources working%n", working.size(), PROBES.size());

    boolean cyrillic =
        PROBES.stream()
            .anyMatch(
                probe -> attempt(new Probe(probe.source(), List.<String[]>of(CYRILLIC))) == null);
    System.out.printf("non-ASCII search: %s%n", cyrillic ? "OK" : "BROKEN");
    assertThat(cyrillic)
        .withFailMessage("No source could download a Cyrillic track — check query encoding.")
        .isTrue();
    assertThat(broken)
        .withFailMessage(
            "These music sources no longer serve a download: %s. Working: %s. "
                + "They are scraped third-party APIs and are expected to rot — "
                + "see docs/music-sources.md for how each one works and where to look.",
            broken, working)
        .isEmpty();
  }

  /** Returns null when the source delivered a playable track, or why it did not. */
  private String attempt(Probe probe) {
    SourcedMusicDownloader downloader = new SourcedMusicDownloader(List.of(probe.source()));
    for (String[] query : probe.queries()) {
      Path folder = library.resolve(probe.source().name() + "-" + query[1].replace(' ', '-'));
      downloader.download(detection(query[0], query[1]), folder);
      Path expected = folder.resolve(query[0] + " - " + query[1] + ".mp3");
      try {
        if (Files.isRegularFile(expected) && Files.size(expected) > 0) {
          return null;
        }
      } catch (IOException exception) {
        return exception.toString();
      }
    }
    return "no probe track could be downloaded";
  }

  private static SoundDetection detection(String artist, String title) {
    return new SoundDetection(
        1L,
        1L,
        DetectionStatus.MATCHED,
        "AudD",
        "key",
        title,
        artist,
        null,
        null,
        null,
        Instant.EPOCH,
        Instant.EPOCH,
        1,
        null);
  }
}
