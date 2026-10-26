package io.github.kalpak44.songradar.detection.shazam;

/**
 * A fixed-size radix-2 Cooley-Tukey FFT. The fingerprint needs one 2048-point transform per 128
 * input samples — a few hundred per recording — so the tables are built once and reused.
 */
final class Fft {

  private final int size;
  private final int[] reversed;
  private final float[] cos;
  private final float[] sin;
  private final float[] real;
  private final float[] imaginary;

  Fft(int size) {
    if (Integer.bitCount(size) != 1) {
      throw new IllegalArgumentException("FFT size must be a power of two: " + size);
    }
    this.size = size;
    this.reversed = new int[size];
    this.cos = new float[size / 2];
    this.sin = new float[size / 2];
    this.real = new float[size];
    this.imaginary = new float[size];
    int bits = Integer.numberOfTrailingZeros(size);
    for (int index = 0; index < size; index++) {
      reversed[index] = Integer.reverse(index) >>> (32 - bits);
    }
    for (int index = 0; index < size / 2; index++) {
      cos[index] = (float) Math.cos(-2 * Math.PI * index / size);
      sin[index] = (float) Math.sin(-2 * Math.PI * index / size);
    }
  }

  /**
   * Transforms {@code input} and writes the squared magnitude of bins {@code 0..magnitudes.length}
   * into {@code magnitudes}, which is all the peak search reads.
   */
  void squaredMagnitudes(float[] input, float[] magnitudes) {
    for (int index = 0; index < size; index++) {
      real[index] = input[reversed[index]];
      imaginary[index] = 0;
    }
    for (int span = 2; span <= size; span <<= 1) {
      int half = span / 2;
      int step = size / span;
      for (int start = 0; start < size; start += span) {
        for (int offset = 0; offset < half; offset++) {
          int twiddle = offset * step;
          int even = start + offset;
          int odd = even + half;
          float oddReal = real[odd] * cos[twiddle] - imaginary[odd] * sin[twiddle];
          float oddImaginary = real[odd] * sin[twiddle] + imaginary[odd] * cos[twiddle];
          real[odd] = real[even] - oddReal;
          imaginary[odd] = imaginary[even] - oddImaginary;
          real[even] += oddReal;
          imaginary[even] += oddImaginary;
        }
      }
    }
    for (int bin = 0; bin < magnitudes.length; bin++) {
      magnitudes[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
    }
  }
}
