package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

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
}
