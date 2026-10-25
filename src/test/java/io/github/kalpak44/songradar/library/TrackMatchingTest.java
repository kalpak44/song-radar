package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrackMatchingTest {

  @Test
  void scoresAnExactMatchHighest() {
    assertThat(TrackMatching.score("Get Lucky - Daft Punk", "Daft Punk", "Get Lucky")).isEqualTo(1);
  }

  @Test
  void ignoresPunctuationAndCase() {
    assertThat(TrackMatching.score("DAFT PUNK: Get Lucky!", "Daft Punk", "Get Lucky")).isEqualTo(1);
  }

  @Test
  void rejectsKaraokeUploadsThatContainEveryWord() {
    double score =
        TrackMatching.score(
            "Get Lucky (Originally Performed By Daft Punk) [Karaoke]", "Daft Punk", "Get Lucky");
    assertThat(score).isLessThan(TrackMatching.MINIMUM_SCORE);
  }

  @Test
  void keepsAKaraokeUploadWhenKaraokeWasDetected() {
    double score = TrackMatching.score("Get Lucky Karaoke", "Daft Punk", "Get Lucky Karaoke");
    assertThat(score).isGreaterThanOrEqualTo(TrackMatching.MINIMUM_SCORE);
  }

  @Test
  void scoresAnUnrelatedUploadBelowTheThreshold() {
    assertThat(TrackMatching.score("Rammstein - Sonne", "Daft Punk", "Get Lucky"))
        .isLessThan(TrackMatching.MINIMUM_SCORE);
  }

  @Test
  void treatsMissingTextAsNoMatch() {
    assertThat(TrackMatching.score(null, "Daft Punk", "Get Lucky")).isZero();
    assertThat(TrackMatching.score("Daft Punk - Get Lucky", null, null)).isZero();
  }
}
