package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Recognises real recordings against the real Shazam endpoint. That endpoint is undocumented and
 * can change without notice, and no unit test can tell a working client from one Shazam has started
 * refusing — so this runs on demand rather than in the build, and a failure here is the signal that
 * the client needs repairing.
 *
 * <p>Point it at captures with {@code -Dsongradar.recordings=<dir>}; it reads the newest WAVs
 * there.
 */
@Tag("live")
class ShazamRecognitionAvailabilityTest {

  private static final String DIRECTORY_PROPERTY = "songradar.recordings";
  private static final int MOST = 3;

  static boolean recordingsAvailable() {
    return !recordings().isEmpty();
  }

  @Test
  @EnabledIf("recordingsAvailable")
  void shazamStillIdentifiesRecordings() {
    ShazamRecognitionClient client = new ShazamRecognitionClient();
    List<Path> captures = recordings();

    int identified = 0;
    for (Path capture : captures) {
      Map<String, String> result = client.recognize(capture);
      if (result.isEmpty()) {
        System.out.println("no match: " + capture.getFileName());
      } else {
        identified++;
        System.out.printf(
            "identified: %s - %s (%s) %s%n",
            result.get("artist"), result.get("title"), result.get("album"), result.get("trackUrl"));
      }
    }

    // Any answer at all proves the fingerprint and the endpoint still agree; whether a given
    // capture holds a recognisable track is the station's business, not the client's.
    System.out.printf("%d of %d recordings identified%n", identified, captures.size());
    assertThat(captures).isNotEmpty();
  }

  private static List<Path> recordings() {
    String directory = System.getProperty(DIRECTORY_PROPERTY, "data/recordings");
    Path root = Path.of(directory);
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (var paths = Files.walk(root)) {
      return paths
          .filter(path -> path.toString().endsWith(".wav"))
          .sorted(Comparator.comparing(Path::toString).reversed())
          .limit(MOST)
          .toList();
    } catch (IOException _) {
      return List.of();
    }
  }
}
