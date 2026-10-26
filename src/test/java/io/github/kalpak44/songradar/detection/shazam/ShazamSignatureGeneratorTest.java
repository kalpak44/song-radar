package io.github.kalpak44.songradar.detection.shazam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.songradar.detection.shazam.ShazamSignature.FrequencyBand;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is only useful if its peaks land on the frequencies that are actually present — a
 * transposed or mis-scaled bin matches nothing, and the service reports that as "no match" rather
 * than as an error, so nothing downstream would notice.
 */
class ShazamSignatureGeneratorTest {

  private static final int RATE = WavDecoder.TARGET_SAMPLE_RATE;
  private static final double BIN_HZ = 16000.0 / 2 / 1024 / 64;

  @Test
  void peaksLandOnTheFrequenciesPresentInTheAudio() {
    ShazamSignature signature = ShazamSignatureGenerator.fromSamples(tones(400, 1000, 2500, 4200));

    assertThat(signature.peakCount()).isPositive();
    assertThat(frequencies(signature, FrequencyBand.HZ_250_520)).allMatch(hz -> near(hz, 400));
    assertThat(frequencies(signature, FrequencyBand.HZ_1450_3500)).allMatch(hz -> near(hz, 2500));
    assertThat(frequencies(signature, FrequencyBand.HZ_3500_5500)).allMatch(hz -> near(hz, 4200));
  }

  @Test
  void aToneIsFiledUnderTheBandThatContainsIt() {
    ShazamSignature low = ShazamSignatureGenerator.fromSamples(tones(300, 1000, 2500, 4200));
    ShazamSignature high = ShazamSignatureGenerator.fromSamples(tones(400, 1000, 2500, 5000));

    assertThat(frequencies(low, FrequencyBand.HZ_250_520)).allMatch(hz -> near(hz, 300));
    assertThat(frequencies(high, FrequencyBand.HZ_3500_5500)).allMatch(hz -> near(hz, 5000));
  }

  @Test
  void silenceProducesNoPeaksAtAll() {
    assertThat(ShazamSignatureGenerator.fromSamples(new short[12 * RATE]).peakCount()).isZero();
  }

  @Test
  void aShortRecordingIsPaddedToTheTwelveSecondsTheFormatCarries() {
    short[] threeSeconds = new short[3 * RATE];
    System.arraycopy(tones(400, 1000, 2500, 4200), 0, threeSeconds, 0, threeSeconds.length);

    ShazamSignature signature = ShazamSignatureGenerator.fromSamples(threeSeconds);

    assertThat(signature.sampleCount()).isEqualTo(12 * RATE);
    assertThat(signature.sampleMillis()).isEqualTo(12_000);
    assertThat(signature.peakCount()).isPositive();
  }

  /** Every pass number has to fit the deltas the encoding is built from. */
  @Test
  void passNumbersIncreaseWithinEachBand() {
    ShazamSignature signature = ShazamSignatureGenerator.fromSamples(tones(400, 1000, 2500, 4200));

    for (FrequencyBand band : FrequencyBand.values()) {
      assertThat(signature.peaks(band))
          .extracting(ShazamSignature.FrequencyPeak::fftPassNumber)
          .isSorted()
          .allMatch(pass -> pass >= 0);
    }
  }

  private static List<Double> frequencies(ShazamSignature signature, FrequencyBand band) {
    return signature.peaks(band).stream()
        .map(peak -> peak.correctedPeakFrequencyBin() * BIN_HZ)
        .toList();
  }

  /** Within one FFT bin of the tone: 16 kHz over 2048 points is 7.8 Hz per bin. */
  private static boolean near(double actualHz, double expectedHz) {
    return Math.abs(actualHz - expectedHz) <= 8.0;
  }

  /**
   * Steady tones are deliberately not used: the peak search demands a local maximum in time as well
   * as frequency, so an unchanging signal yields nothing. Gating the tones gives it the onsets that
   * real music supplies.
   */
  private static short[] tones(double... hz) {
    short[] samples = new short[12 * RATE];
    for (int index = 0; index < samples.length; index++) {
      if ((index / 3000) % 2 != 0) {
        continue;
      }
      double value = 0;
      for (double frequency : hz) {
        value += Math.sin(2 * Math.PI * frequency * index / RATE);
      }
      samples[index] = (short) (4000 * value);
    }
    return samples;
  }
}
