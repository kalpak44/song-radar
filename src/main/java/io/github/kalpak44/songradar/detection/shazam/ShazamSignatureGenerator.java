package io.github.kalpak44.songradar.detection.shazam;

import io.github.kalpak44.songradar.detection.shazam.ShazamSignature.FrequencyBand;
import io.github.kalpak44.songradar.detection.shazam.ShazamSignature.FrequencyPeak;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds a Shazam fingerprint from 16 kHz mono audio.
 *
 * <p>The procedure is Shazam's own, as documented by the SongRec project: a 2048-point FFT every
 * 128 samples over a Hann window, peak values spread across neighbouring bins and passes, then the
 * peaks that survive as local maxima in both frequency and time, bucketed into four bands. The
 * constants below (46/49 pass lookbacks, the neighbour offsets, the magnitude scaling) are part of
 * the wire format rather than choices of ours, so they are reproduced exactly.
 */
public final class ShazamSignatureGenerator {

  private static final int FFT_SIZE = 2048;
  private static final int BINS = 1025;
  private static final int CHUNK = 128;
  private static final int RING_SIZE = 256;
  private static final int CLIP_SECONDS = 12;
  private static final int CLIP_SAMPLES = CLIP_SECONDS * WavDecoder.TARGET_SAMPLE_RATE;

  /** Hann window with the leading and trailing zeroes omitted, as Shazam applies it. */
  private static final float[] HANN_WINDOW = hannWindow();

  private final short[] ringBufferOfSamples = new short[FFT_SIZE];
  private final float[] windowedSamples = new float[FFT_SIZE];
  private final float[][] fftOutputs = new float[RING_SIZE][BINS];
  private final float[][] spreadFftOutputs = new float[RING_SIZE][BINS];
  private final Fft fft = new Fft(FFT_SIZE);

  private int ringBufferIndex;
  private int fftOutputsIndex;
  private int spreadFftOutputsIndex;
  private int spreadFftsDone;
  private ShazamSignature signature;

  /**
   * Fingerprints a recording, taking the middle twelve seconds. The middle rather than the start
   * because a capture that begins mid-crossfade or on an announcement still usually has the track
   * itself by the time it reaches the centre.
   */
  public static ShazamSignature fromRecording(Path wav) throws IOException {
    return fromSamples(WavDecoder.decodeToMono16kHz(wav));
  }

  public static ShazamSignature fromSamples(short[] mono16kHz) {
    short[] clip = new short[CLIP_SAMPLES];
    if (mono16kHz.length <= CLIP_SAMPLES) {
      System.arraycopy(mono16kHz, 0, clip, 0, mono16kHz.length);
    } else {
      System.arraycopy(mono16kHz, mono16kHz.length / 2 - CLIP_SAMPLES / 2, clip, 0, CLIP_SAMPLES);
    }
    return new ShazamSignatureGenerator().process(clip);
  }

  private ShazamSignature process(short[] samples) {
    signature = new ShazamSignature(WavDecoder.TARGET_SAMPLE_RATE, samples.length);
    for (int offset = 0; offset + CHUNK <= samples.length; offset += CHUNK) {
      doFft(samples, offset);
      doPeakSpreading();
      spreadFftsDone++;
      if (spreadFftsDone >= 46) {
        doPeakRecognition();
      }
    }
    return signature;
  }

  private void doFft(short[] samples, int offset) {
    System.arraycopy(samples, offset, ringBufferOfSamples, ringBufferIndex, CHUNK);
    ringBufferIndex = (ringBufferIndex + CHUNK) & (FFT_SIZE - 1);

    for (int index = 0; index < FFT_SIZE; index++) {
      windowedSamples[index] =
          ringBufferOfSamples[(index + ringBufferIndex) & (FFT_SIZE - 1)] * HANN_WINDOW[index];
    }

    float[] output = fftOutputs[fftOutputsIndex];
    fft.squaredMagnitudes(windowedSamples, output);
    for (int bin = 0; bin < BINS; bin++) {
      output[bin] = Math.max(output[bin] / (1 << 17), 0.0000000001f);
    }
    fftOutputsIndex = (fftOutputsIndex + 1) & (RING_SIZE - 1);
  }

  private void doPeakSpreading() {
    float[] latest = fftOutputs[(fftOutputsIndex - 1) & (RING_SIZE - 1)];
    float[] spread = spreadFftOutputs[spreadFftOutputsIndex];
    System.arraycopy(latest, 0, spread, 0, BINS);

    for (int position = 0; position <= 1022; position++) {
      spread[position] =
          Math.max(spread[position], Math.max(spread[position + 1], spread[position + 2]));
    }

    for (int former : new int[] {1, 3, 6}) {
      float[] earlier = spreadFftOutputs[(spreadFftOutputsIndex - former) & (RING_SIZE - 1)];
      for (int position = 0; position < BINS; position++) {
        earlier[position] = Math.max(earlier[position], spread[position]);
      }
    }

    spreadFftOutputsIndex = (spreadFftOutputsIndex + 1) & (RING_SIZE - 1);
  }

  private static final int[] FREQUENCY_NEIGHBOURS = {-10, -7, -4, -3, 1, 2, 5, 8};
  private static final int[] TIME_NEIGHBOURS = {
    -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249
  };

  private void doPeakRecognition() {
    float[] fftMinus46 = fftOutputs[(fftOutputsIndex - 46) & (RING_SIZE - 1)];
    float[] fftMinus49 = spreadFftOutputs[(spreadFftOutputsIndex - 49) & (RING_SIZE - 1)];

    for (int bin = 10; bin <= 1014; bin++) {
      if (fftMinus46[bin] < 1.0f / 64.0f || fftMinus46[bin] < fftMinus49[bin - 1]) {
        continue;
      }

      float maxNeighbour = 0.0f;
      for (int neighbour : FREQUENCY_NEIGHBOURS) {
        maxNeighbour = Math.max(maxNeighbour, fftMinus49[bin + neighbour]);
      }
      if (fftMinus46[bin] <= maxNeighbour) {
        continue;
      }

      for (int other : TIME_NEIGHBOURS) {
        float[] otherFft = spreadFftOutputs[(spreadFftOutputsIndex + other) & (RING_SIZE - 1)];
        maxNeighbour = Math.max(maxNeighbour, otherFft[bin - 1]);
      }
      if (fftMinus46[bin] <= maxNeighbour) {
        continue;
      }

      float magnitude = magnitudeOf(fftMinus46[bin]);
      float magnitudeBefore = magnitudeOf(fftMinus46[bin - 1]);
      float magnitudeAfter = magnitudeOf(fftMinus46[bin + 1]);
      float variation = magnitude * 2.0f - magnitudeBefore - magnitudeAfter;
      if (variation <= 0.0f) {
        // Not a true spectral peak once its own neighbours are weighed; dividing here would
        // overflow the frequency bin rather than describe anything.
        continue;
      }
      int correctedBin = bin * 64 + (int) ((magnitudeAfter - magnitudeBefore) * 32.0f / variation);
      if (correctedBin < 0 || correctedBin > 0xffff) {
        continue;
      }

      FrequencyBand band = bandOf(correctedBin * (16000.0f / 2 / 1024 / 64));
      if (band == null) {
        continue;
      }
      signature.add(
          band, new FrequencyPeak(spreadFftsDone - 46, (int) magnitude & 0xffff, correctedBin));
    }
  }

  private static float magnitudeOf(float binValue) {
    return Math.max((float) Math.log(binValue), 1.0f / 64.0f) * 1477.3f + 6144.0f;
  }

  private static FrequencyBand bandOf(float frequencyHz) {
    int hz = (int) frequencyHz;
    if (hz >= 250 && hz < 520) {
      return FrequencyBand.HZ_250_520;
    }
    if (hz >= 520 && hz < 1450) {
      return FrequencyBand.HZ_520_1450;
    }
    if (hz >= 1450 && hz < 3500) {
      return FrequencyBand.HZ_1450_3500;
    }
    if (hz >= 3500 && hz <= 5500) {
      return FrequencyBand.HZ_3500_5500;
    }
    return null;
  }

  private static float[] hannWindow() {
    float[] window = new float[FFT_SIZE];
    for (int index = 0; index < FFT_SIZE; index++) {
      window[index] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * (index + 1) / (FFT_SIZE + 1.0)));
    }
    return window;
  }
}
