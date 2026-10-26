package io.github.kalpak44.songradar.detection.shazam;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kalpak44.songradar.detection.shazam.ShazamSignature.FrequencyBand;
import io.github.kalpak44.songradar.detection.shazam.ShazamSignature.FrequencyPeak;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

/**
 * The binary layout is Shazam's, not ours: a field in the wrong place is rejected outright, and the
 * service reports that the same way it reports an unknown recording. These assertions pin the
 * layout so a refactor cannot quietly turn every recognition into a no-match.
 */
class ShazamSignatureTest {

  private static final int HEADER_SIZE = 48;

  @Test
  void theHeaderCarriesTheMagicsSizesAndSampleCountShazamExpects() {
    ShazamSignature signature = new ShazamSignature(16_000, 192_000);
    signature.add(FrequencyBand.HZ_520_1450, new FrequencyPeak(3, 6500, 8192));

    ByteBuffer encoded = ByteBuffer.wrap(signature.encodeToBinary()).order(ByteOrder.LITTLE_ENDIAN);

    assertThat(encoded.getInt(0)).isEqualTo(0xcafe2580);
    assertThat(encoded.getInt(12)).isEqualTo(0x94119c00);
    assertThat(encoded.getInt(8)).isEqualTo(encoded.limit() - HEADER_SIZE);
    assertThat(encoded.getInt(HEADER_SIZE + 4)).isEqualTo(encoded.limit() - HEADER_SIZE);
    assertThat(encoded.getInt(HEADER_SIZE)).isEqualTo(0x40000000);
    assertThat(encoded.getInt(28)).isEqualTo(3 << 27);
    // The sample count is stored with 0.24 s of the sample rate added on.
    assertThat(encoded.getInt(40)).isEqualTo(192_000 + 3_840);
    assertThat(encoded.getInt(44)).isEqualTo((15 << 19) + 0x40000);
  }

  @Test
  void theChecksumCoversEverythingAfterItself() {
    ShazamSignature signature = new ShazamSignature(16_000, 192_000);
    signature.add(FrequencyBand.HZ_250_520, new FrequencyPeak(1, 6200, 4096));

    byte[] encoded = signature.encodeToBinary();

    CRC32 expected = new CRC32();
    expected.update(encoded, 8, encoded.length - 8);
    assertThat(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        .isEqualTo((int) expected.getValue());
  }

  @Test
  void peaksAreWrittenPerBandAsDeltasAndPaddedToFourBytes() {
    ShazamSignature signature = new ShazamSignature(16_000, 192_000);
    signature.add(FrequencyBand.HZ_1450_3500, new FrequencyPeak(4, 6300, 1024));
    signature.add(FrequencyBand.HZ_1450_3500, new FrequencyPeak(9, 6400, 2048));

    ByteBuffer encoded = ByteBuffer.wrap(signature.encodeToBinary()).order(ByteOrder.LITTLE_ENDIAN);

    int chunk = HEADER_SIZE + 8;
    assertThat(encoded.getInt(chunk)).isEqualTo(0x60030040 + FrequencyBand.HZ_1450_3500.ordinal());
    assertThat(encoded.getInt(chunk + 4)).isEqualTo(10);
    assertThat(encoded.get(chunk + 8)).isEqualTo((byte) 4);
    assertThat(encoded.getShort(chunk + 9)).isEqualTo((short) 6300);
    assertThat(encoded.getShort(chunk + 11)).isEqualTo((short) 1024);
    // Second peak is five passes later, so it is a delta rather than an absolute position.
    assertThat(encoded.get(chunk + 13)).isEqualTo((byte) 5);
    assertThat(encoded.getShort(chunk + 14)).isEqualTo((short) 6400);
    assertThat(encoded.getShort(chunk + 16)).isEqualTo((short) 2048);
    // Ten bytes of peaks are padded to twelve.
    assertThat(encoded.limit()).isEqualTo(chunk + 8 + 12);
  }

  /** A pass gap wider than a byte restarts the delta count with an absolute pass number. */
  @Test
  void aWideGapBetweenPassesIsEscaped() {
    ShazamSignature signature = new ShazamSignature(16_000, 192_000);
    signature.add(FrequencyBand.HZ_250_520, new FrequencyPeak(0, 6100, 2048));
    signature.add(FrequencyBand.HZ_250_520, new FrequencyPeak(400, 6200, 2050));

    ByteBuffer encoded = ByteBuffer.wrap(signature.encodeToBinary()).order(ByteOrder.LITTLE_ENDIAN);

    int peaks = HEADER_SIZE + 8 + 8;
    assertThat(encoded.get(peaks)).isEqualTo((byte) 0);
    assertThat(encoded.get(peaks + 5)).isEqualTo((byte) 0xff);
    assertThat(encoded.getInt(peaks + 6)).isEqualTo(400);
    assertThat(encoded.get(peaks + 10)).isEqualTo((byte) 0);
  }

  @Test
  void theUriIsTheBase64OfTheBinaryUnderShazamsOwnMediaType() {
    ShazamSignature signature = new ShazamSignature(16_000, 192_000);
    signature.add(FrequencyBand.HZ_3500_5500, new FrequencyPeak(2, 6000, 32_768));

    String uri = signature.encodeToUri();

    assertThat(uri).startsWith("data:audio/vnd.shazam.sig;base64,");
    assertThat(
            Base64.getDecoder().decode(uri.substring("data:audio/vnd.shazam.sig;base64,".length())))
        .isEqualTo(signature.encodeToBinary());
  }

  @Test
  void theDurationItReportsIsDerivedFromTheSampleCount() {
    assertThat(new ShazamSignature(16_000, 192_000).sampleMillis()).isEqualTo(12_000);
  }
}
