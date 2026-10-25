package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import de.sciss.jump3r.lowlevel.LameEncoder;
import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcedMusicDownloaderTest {

  private static byte[] track;
  private static byte[] clip;

  @TempDir Path library;

  /** Real MP3 bytes: the downloader files only what it can parse, so stub audio has to be audio. */
  @BeforeAll
  static void encodeFixtures() {
    track = silence(40);
    clip = silence(5);
  }

  private static byte[] silence(int seconds) {
    AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
    LameEncoder encoder =
        new LameEncoder(
            format, 64, LameEncoder.CHANNEL_MODE_AUTO, LameEncoder.QUALITY_LOWEST, false);
    byte[] pcm = new byte[encoder.getPCMBufferSize()];
    byte[] encoded = new byte[encoder.getMP3BufferSize()];
    ByteArrayOutputStream mp3 = new ByteArrayOutputStream();
    long blocks = (long) seconds * 44100 * 4 / pcm.length;
    for (long i = 0; i < blocks; i++) {
      mp3.write(encoded, 0, encoder.encodeBuffer(pcm, 0, pcm.length, encoded));
    }
    mp3.write(encoded, 0, encoder.encodeFinish(encoded));
    encoder.close();
    return mp3.toByteArray();
  }

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
  private static TrackSource serving(String name, byte[] audio) {
    return new TrackSource() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Optional<SourceTrack> find(String artist, String title) {
        return Optional.of(new SourceTrack(name, title, artist, 1000, out -> out.write(audio)));
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
    new SourcedMusicDownloader(List.of(serving("first", track)))
        .download(detection("Daft Punk", "Get Lucky"), library.resolve("Radio One"));

    assertThat(library.resolve("Radio One/Daft Punk - Get Lucky.mp3")).exists();
  }

  @Test
  void usesTheNextSourceWhenTheFirstHasNothing() {
    new SourcedMusicDownloader(List.of(finding("empty", false), serving("second", track)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(library.resolve("Daft Punk - Get Lucky.mp3")).exists();
  }

  @Test
  void usesTheNextSourceWhenTheFirstFailsWhileServing() {
    new SourcedMusicDownloader(List.of(finding("broken", true), serving("second", track)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(library.resolve("Daft Punk - Get Lucky.mp3")).exists();
  }

  @Test
  void overwritesATrackThatIsAlreadyThere() throws IOException {
    Path target = library.resolve("Daft Punk - Get Lucky.mp3");
    Files.writeString(target, "stale");

    new SourcedMusicDownloader(List.of(serving("first", track)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(Files.size(target)).isGreaterThan("stale".length());
  }

  @Test
  void leavesNothingBehindWhenNoSourceHasTheTrack() throws IOException {
    new SourcedMusicDownloader(List.of(finding("empty", false)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(contents()).isEmpty();
  }

  @Test
  void rejectsASourceThatAnswersWithSomethingOtherThanAudio() throws IOException {
    new SourcedMusicDownloader(
            List.of(serving("page", "<html>not found</html>".getBytes(StandardCharsets.UTF_8))))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(contents()).isEmpty();
  }

  @Test
  void rejectsAStreamThatStoppedAfterAFewSeconds() throws IOException {
    new SourcedMusicDownloader(List.of(serving("truncated", clip)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(contents()).isEmpty();
  }

  @Test
  void fallsThroughToASourceThatServesRealAudio() {
    new SourcedMusicDownloader(List.of(serving("truncated", clip), serving("second", track)))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(library.resolve("Daft Punk - Get Lucky.mp3")).exists();
  }

  /** A half-written download must not be listed by the file manager as a playable track. */
  @Test
  void stagesTheDownloadUnderTheNameTheFileManagerHides() throws IOException {
    List<String> whileDownloading = new ArrayList<>();
    TrackSource watching =
        new TrackSource() {
          @Override
          public String name() {
            return "watching";
          }

          @Override
          public Optional<SourceTrack> find(String artist, String title) {
            return Optional.of(
                new SourceTrack(
                    name(),
                    title,
                    artist,
                    1000,
                    out -> {
                      try (var entries = Files.list(library)) {
                        entries
                            .map(path -> path.getFileName().toString())
                            .forEach(whileDownloading::add);
                      }
                      out.write(track);
                    }));
          }
        };

    new SourcedMusicDownloader(List.of(watching))
        .download(detection("Daft Punk", "Get Lucky"), library);

    assertThat(whileDownloading)
        .isNotEmpty()
        .allMatch(name -> name.startsWith(".songradar-download-"));
    assertThat(contents()).containsExactly("Daft Punk - Get Lucky.mp3");
  }

  @Test
  void doesNotSearchForADetectionWithNoArtistOrTitle() throws IOException {
    new SourcedMusicDownloader(List.of(serving("first", track)))
        .download(detection(null, null), library);

    assertThat(contents()).isEmpty();
  }

  @Test
  void keepsTheFileNameUsableWhenOnlyOneOfTheTwoIsKnown() {
    new SourcedMusicDownloader(List.of(serving("first", track)))
        .download(detection(null, "Get Lucky"), library);

    assertThat(library.resolve("Get Lucky.mp3")).exists();
  }

  @Test
  void stripsPathSeparatorsOutOfTheFileName() {
    new SourcedMusicDownloader(List.of(serving("first", track)))
        .download(detection("AC/DC", "Back in Black"), library);

    assertThat(library.resolve("AC DC - Back in Black.mp3")).exists();
  }

  private List<String> contents() throws IOException {
    try (var entries = Files.list(library)) {
      return entries.map(path -> path.getFileName().toString()).toList();
    }
  }
}
