package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

/**
 * The chain is ordered by the audio a source delivers, best first, because the first source that
 * answers wins and nothing downstream gets another chance at a better copy. Spring decides the
 * order from these annotations, and two sources sharing a value leaves the choice between them to
 * classpath scanning rather than to anyone's judgement — which is what this is here to prevent.
 */
class TrackSourceOrderTest {

  /** In order: MP3 320 untouched, AAC 320, Opus ~160, MP3 128 or AAC, MP3 128. */
  private static final List<Class<? extends TrackSource>> BY_QUALITY =
      List.of(
          AudiusTrackSource.class,
          JioSaavnTrackSource.class,
          YtDlpTrackSource.class,
          SoundCloudTrackSource.class,
          BandcampTrackSource.class);

  @Test
  void sourcesAreTriedBestAudioFirst() {
    List<Integer> orders = BY_QUALITY.stream().map(TrackSourceOrderTest::order).toList();

    assertThat(orders).isSorted();
    assertThat(orders).doesNotHaveDuplicates();
  }

  private static int order(Class<? extends TrackSource> source) {
    Order annotation = source.getAnnotation(Order.class);
    assertThat(annotation)
        .withFailMessage("%s has no @Order, so its place in the chain is undefined", source)
        .isNotNull();
    return annotation.value();
  }
}
