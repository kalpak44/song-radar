package io.github.kalpak44.songradar.detection.shazam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the 16-bit PCM WAV files the listener writes and hands back mono 16 kHz samples, the only
 * form Shazam's fingerprint is defined over.
 */
public final class WavDecoder {

  /**
   * Shazam downsamples to 16 kHz before fingerprinting, so every signature is built at this rate.
   */
  public static final int TARGET_SAMPLE_RATE = 16_000;

  private static final int MAX_CHANNELS = 8;

  private WavDecoder() {}

  public static short[] decodeToMono16kHz(Path wav) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN);
    if (buffer.remaining() < 12
        || buffer.getInt(0) != 0x46464952
        || buffer.getInt(8) != 0x45564157) {
      throw new IOException("the recording is not a RIFF/WAVE file");
    }

    int channels = 0;
    int sampleRate = 0;
    int bitsPerSample = 0;
    int format = 0;
    int dataOffset = -1;
    int dataLength = 0;

    int cursor = 12;
    while (cursor + 8 <= buffer.limit()) {
      int chunkId = buffer.getInt(cursor);
      int chunkSize = buffer.getInt(cursor + 4);
      int body = cursor + 8;
      if (chunkSize < 0 || body + chunkSize > buffer.limit()) {
        // A recording cut short mid-write still has a usable prefix; trust the file, not the
        // header.
        chunkSize = buffer.limit() - body;
      }
      if (chunkId == 0x20746d66 && chunkSize >= 16) {
        format = buffer.getShort(body) & 0xffff;
        channels = buffer.getShort(body + 2) & 0xffff;
        sampleRate = buffer.getInt(body + 4);
        bitsPerSample = buffer.getShort(body + 14) & 0xffff;
      } else if (chunkId == 0x61746164) {
        dataOffset = body;
        dataLength = chunkSize;
      }
      cursor = body + chunkSize + (chunkSize & 1);
    }

    if (dataOffset < 0 || dataLength <= 0) {
      throw new IOException("the recording has no WAVE data chunk");
    }
    if (format != 1 || bitsPerSample != 16) {
      throw new IOException("the recording is not 16-bit PCM audio");
    }
    if (channels < 1 || channels > MAX_CHANNELS) {
      throw new IOException("the recording has an unsupported channel count: " + channels);
    }
    if (sampleRate < 1) {
      throw new IOException("the recording has an unsupported sample rate: " + sampleRate);
    }

    int frames = dataLength / (2 * channels);
    if (frames < 1) {
      throw new IOException("the recording contains no audio frames");
    }

    float[] mono = new float[frames];
    for (int frame = 0; frame < frames; frame++) {
      int base = dataOffset + frame * 2 * channels;
      int sum = 0;
      for (int channel = 0; channel < channels; channel++) {
        sum += buffer.getShort(base + channel * 2);
      }
      mono[frame] = (float) sum / channels;
    }
    return resample(mono, sampleRate);
  }

  /**
   * Linear interpolation is enough here: the fingerprint keeps the position of spectral peaks, not
   * the waveform, and the bands it uses top out at 5.5 kHz.
   */
  private static short[] resample(float[] samples, int sourceRate) {
    if (sourceRate == TARGET_SAMPLE_RATE) {
      short[] converted = new short[samples.length];
      for (int index = 0; index < samples.length; index++) {
        converted[index] = clamp(samples[index]);
      }
      return converted;
    }
    double ratio = (double) TARGET_SAMPLE_RATE / sourceRate;
    int length = (int) (samples.length * ratio);
    short[] resampled = new short[Math.max(length, 1)];
    for (int index = 0; index < resampled.length; index++) {
      double position = index / ratio;
      int left = (int) position;
      int right = Math.min(left + 1, samples.length - 1);
      double fraction = position - left;
      resampled[index] =
          clamp((float) (samples[left] * (1.0 - fraction) + samples[right] * fraction));
    }
    return resampled;
  }

  private static short clamp(float value) {
    return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
  }
}
