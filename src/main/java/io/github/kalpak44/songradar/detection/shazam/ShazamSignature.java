package io.github.kalpak44.songradar.detection.shazam;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A decoded Shazam fingerprint: the spectral peaks of a recording, grouped into the four frequency
 * bands the format defines, plus the binary encoding the service expects.
 *
 * <p>No audio is kept or sent — a peak is only a {@code (pass, magnitude, frequency bin)} triple,
 * which is why the upload is a fingerprint rather than the recording itself.
 */
public final class ShazamSignature {

  static final String DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,";

  private static final int MAGIC_1 = 0xcafe2580;
  private static final int MAGIC_2 = 0x94119c00;
  private static final int HEADER_SIZE = 48;
  private static final int BAND_CHUNK_ID = 0x60030040;
  private static final int SAMPLE_RATE_ID_16000 = 3;

  private final int sampleRateHz;
  private final int sampleCount;
  private final List<List<FrequencyPeak>> bands;

  ShazamSignature(int sampleRateHz, int sampleCount) {
    this.sampleRateHz = sampleRateHz;
    this.sampleCount = sampleCount;
    this.bands = new ArrayList<>(FrequencyBand.values().length);
    for (int band = 0; band < FrequencyBand.values().length; band++) {
      bands.add(new ArrayList<>());
    }
  }

  void add(FrequencyBand band, FrequencyPeak peak) {
    bands.get(band.ordinal()).add(peak);
  }

  public int sampleCount() {
    return sampleCount;
  }

  public int sampleRateHz() {
    return sampleRateHz;
  }

  public int peakCount() {
    return bands.stream().mapToInt(List::size).sum();
  }

  public int peakCount(FrequencyBand band) {
    return bands.get(band.ordinal()).size();
  }

  List<FrequencyPeak> peaks(FrequencyBand band) {
    return List.copyOf(bands.get(band.ordinal()));
  }

  /** The duration the fingerprint covers, which the service wants alongside the signature. */
  public int sampleMillis() {
    return (int) ((long) sampleCount * 1000 / sampleRateHz);
  }

  public String encodeToUri() {
    return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encodeToBinary());
  }

  byte[] encodeToBinary() {
    if (sampleRateHz != WavDecoder.TARGET_SAMPLE_RATE) {
      throw new IllegalStateException("only 16 kHz signatures are encodable: " + sampleRateHz);
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (FrequencyBand band : FrequencyBand.values()) {
      List<FrequencyPeak> peaks = bands.get(band.ordinal());
      if (peaks.isEmpty()) {
        continue;
      }
      byte[] encodedPeaks = encodePeaks(peaks);
      body.writeBytes(littleEndian(BAND_CHUNK_ID + band.ordinal()));
      body.writeBytes(littleEndian(encodedPeaks.length));
      body.writeBytes(encodedPeaks);
      body.writeBytes(new byte[(4 - encodedPeaks.length % 4) % 4]);
    }

    byte[] peakChunks = body.toByteArray();
    int total = HEADER_SIZE + 8 + peakChunks.length;
    ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(MAGIC_1);
    buffer.putInt(0); // crc32, filled in below once the rest of the message exists
    buffer.putInt(total - HEADER_SIZE);
    buffer.putInt(MAGIC_2);
    buffer.putInt(0);
    buffer.putInt(0);
    buffer.putInt(0);
    buffer.putInt(SAMPLE_RATE_ID_16000 << 27);
    buffer.putInt(0);
    buffer.putInt(0);
    buffer.putInt(sampleCount + (int) (sampleRateHz * 0.24f));
    buffer.putInt((15 << 19) + 0x40000);
    buffer.putInt(0x40000000);
    buffer.putInt(total - HEADER_SIZE);
    buffer.put(peakChunks);

    byte[] encoded = buffer.array();
    CRC32 crc = new CRC32();
    crc.update(encoded, 8, encoded.length - 8);
    ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).putInt(4, (int) crc.getValue());
    return encoded;
  }

  /**
   * Peaks are stored as deltas against the previous FFT pass, so a gap wider than a byte has to
   * restart the count with an explicit pass number.
   */
  private static byte[] encodePeaks(List<FrequencyPeak> peaks) {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    int previousPass = 0;
    for (FrequencyPeak peak : peaks) {
      if (peak.fftPassNumber() - previousPass >= 255) {
        encoded.write(0xff);
        encoded.writeBytes(littleEndian(peak.fftPassNumber()));
        previousPass = peak.fftPassNumber();
      }
      encoded.write(peak.fftPassNumber() - previousPass);
      encoded.writeBytes(littleEndianShort(peak.peakMagnitude()));
      encoded.writeBytes(littleEndianShort(peak.correctedPeakFrequencyBin()));
      previousPass = peak.fftPassNumber();
    }
    return encoded.toByteArray();
  }

  private static byte[] littleEndian(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private static byte[] littleEndianShort(int value) {
    return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
  }

  /** The bands Shazam splits the spectrum into, in the order their chunk ids follow. */
  public enum FrequencyBand {
    HZ_250_520,
    HZ_520_1450,
    HZ_1450_3500,
    HZ_3500_5500
  }

  record FrequencyPeak(int fftPassNumber, int peakMagnitude, int correctedPeakFrequencyBin) {}
}
