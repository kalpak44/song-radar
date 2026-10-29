package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class YtDlpTrackSourceTest {

  /** The binary is absent in most environments, and that has to be a miss rather than a crash. */
  @Test
  void aMissingBinaryIsJustAnEmptyResult() {
    YtDlpTrackSource source = new YtDlpTrackSource("/nonexistent/yt-dlp-does-not-exist", "", 5);

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void isNamedForTheCatalogueRatherThanTheTool() {
    assertThat(new YtDlpTrackSource("yt-dlp", "", 5).name()).isEqualTo("YouTube Music");
  }

  /**
   * YouTube Music credits the songwriters alongside the performer, and yt-dlp joins the lot into
   * one string. Carrying that into a search costs the track: every extra name is a word the matcher
   * then looks for in candidates that will never contain it.
   */
  @Test
  void namesATrackAfterItsPerformerRatherThanItsCredits() {
    assertThat(
            entry("ЗОМБ, Трегубов Семён Александрович", List.of("ЗОМБ", "Трегубов Семён"), null)
                .primaryArtist())
        .isEqualTo("ЗОМБ");
  }

  @Test
  void fallsBackToTheJoinedCreditThenToTheChannel() {
    assertThat(entry("Daft Punk", null, "Daft Punk - Topic").primaryArtist())
        .isEqualTo("Daft Punk");
    // A channel that publishes one artist's songs is named for them, with this appended.
    assertThat(entry(null, null, "GAZIROVKA - Topic").primaryArtist()).isEqualTo("GAZIROVKA");
    assertThat(entry(null, null, "KAUFMAN MUSIC").primaryArtist()).isEqualTo("KAUFMAN MUSIC");
    assertThat(entry(null, List.of(), null).primaryArtist()).isNull();
  }

  private static YtDlpTrackSource.Entry entry(String artist, List<String> artists, String channel) {
    return new YtDlpTrackSource.Entry(
        "id", "title", null, artist, artists, channel, null, "Youtube");
  }
}
