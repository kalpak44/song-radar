package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcedMusicDownloaderTest {

  @TempDir Path library;

  private static SoundDetection detection(String artist, String title) {
    return new SoundDetection(
        7L,
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

  /** A source that serves fixed bytes, standing in for a catalogue. */
  private static TrackSource serving(String name, String audio) {
    return new TrackSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Optional<SourceTrack> find(String artist, String title) {
        return Optional.of(
            new SourceTrack(
                name,
                title,
                artist,
                1000,
                out -> out.write(audio.getBytes(StandardCharsets.UTF_8))));
      }
    };
  }

  private static TrackSource finding(String name, boolean found) {
    return new TrackSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Optional<SourceTrack> find(String artist, String title) {
        return found
            ? Optional.of(
                new SourceTrack(
                    name,
                    title,
                    artist,
                    1000,
                    out -> {
                      throw new IOException("the stream died halfway");
                    }))
            : Optional.empty();
      }
    };
  }

  @Test
  void savesTheTrackUnderTheDetectedArtistAndTitle() {
    new SourcedMusicDownloader(List.of(serving("first", "audio")))
        .download(detection("Daft Punk", "Get Lucky"), library.resolve("Radio One"));

    assertThat(library.resolve("Radio One/Daft Punk - Get Lucky.mp3")).exists();
  }

  @Test
  void usesTheNextSourceWhenTheFirstHasNothing() throws IOException {
    new SourcedMusicDownloader(List.of(finding("empty", false), serving("second", "from-second")))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(Files.readString(library.resolve("Daft Punk - Get Lucky.mp3")))
        .isEqualTo("from-second");
  }

  @Test
  void usesTheNextSourceWhenTheFirstFailsWhileServing() throws IOException {
    new SourcedMusicDownloader(List.of(finding("broken", true), serving("second", "from-second")))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(Files.readString(library.resolve("Daft Punk - Get Lucky.mp3")))
        .isEqualTo("from-second");
  }

  @Test
  void overwritesATrackThatIsAlreadyThere() throws IOException {
    Path target = library.resolve("Daft Punk - Get Lucky.mp3");
    Files.writeString(target, "stale");

    new SourcedMusicDownloader(List.of(serving("first", "fresh")))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(Files.readString(target)).isEqualTo("fresh");
  }

  @Test
  void leavesNothingBehindWhenNoSourceHasTheTrack() throws IOException {
    new SourcedMusicDownloader(List.of(finding("empty", false)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    try (var entries = Files.list(library)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void leavesNoPartialFileWhenTheSourceServesNothing() throws IOException {
    new SourcedMusicDownloader(List.of(serving("empty", "")))
        .download(detection("Daft Punk", "Get Lucky"), library);

    try (var entries = Files.list(library)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void doesNotSearchForADetectionWithNoArtistOrTitle() throws IOException {
    new SourcedMusicDownloader(List.of(serving("first", "audio")))
        .download(detection(null, null), library);

    try (var entries = Files.list(library)) {
      assertThat(entries).isEmpty();
    }
  }

  @Test
  void keepsTheFileNameUsableWhenOnlyOneOfTheTwoIsKnown() {
    new SourcedMusicDownloader(List.of(serving("first", "audio")))
        .download(detection(null, "Get Lucky"), library);

    assertThat(library.resolve("Get Lucky.mp3")).exists();
  }

  @Test
  void stripsPathSeparatorsOutOfTheFileName() {
    new SourcedMusicDownloader(List.of(serving("first", "audio")))
        .download(detection("AC/DC", "Back in Black"), library);

    assertThat(library.resolve("AC DC - Back in Black.mp3")).exists();
  }
}
