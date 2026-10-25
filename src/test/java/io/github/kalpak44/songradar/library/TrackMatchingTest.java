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
  void rejectsRemixesAndReworksOfTheDetectedTrack() {
    for (String variant :
        new String[] {
          "Get Lucky (Daft Punk Remix)",
          "Daft Punk - Get Lucky (eaz rework)",
          "Daft Punk Get Lucky bootleg",
          "Get Lucky — Daft Punk mashup",
          "Daft Punk Get Lucky (Radio Edit)",
          "Daft Punk - Get Lucky [live]"
        }) {
      assertThat(TrackMatching.score(variant, "Daft Punk", "Get Lucky"))
          .describedAs(variant)
          .isLessThan(TrackMatching.MINIMUM_SCORE);
    }
  }

  /** Single-word markers are matched whole, or every Oliver is a live recording. */
  @Test
  void doesNotReadAMarkerOutOfTheMiddleOfAWord() {
    assertThat(TrackMatching.score("Oliver Heldens - Gecko", "Oliver Heldens", "Gecko"))
        .isGreaterThanOrEqualTo(TrackMatching.MINIMUM_SCORE);
    assertThat(TrackMatching.score("Editors - Munich", "Editors", "Munich"))
        .isGreaterThanOrEqualTo(TrackMatching.MINIMUM_SCORE);
  }

  @Test
  void keepsAVariantTheDetectionItselfNames() {
    assertThat(TrackMatching.score("Get Lucky (Radio Edit)", "Daft Punk", "Get Lucky Radio Edit"))
        .isGreaterThanOrEqualTo(TrackMatching.MINIMUM_SCORE);
  }

  @Test
  void treatsMissingTextAsNoMatch() {
    assertThat(TrackMatching.score(null, "Daft Punk", "Get Lucky")).isZero();
    assertThat(TrackMatching.score("Daft Punk - Get Lucky", null, null)).isZero();
  }
}
