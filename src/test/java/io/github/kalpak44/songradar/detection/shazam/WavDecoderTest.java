package io.github.kalpak44.songradar.detection.shazam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavDecoderTest {

  @TempDir Path directory;

  @Test
  void stereoIsDownmixedAndResampledTo16kHz() throws IOException {
    Path wav = directory.resolve("capture.wav");
    short[] frames = new short[48_000 * 2];
    for (int frame = 0; frame < 48_000; frame++) {
      frames[frame * 2] = 1000;
      frames[frame * 2 + 1] = 3000;
    }
    Files.write(wav, wav(48_000, 2, frames));

    short[] decoded = WavDecoder.decodeToMono16kHz(wav);

    assertThat(decoded).hasSize(16_000);
    // Both channels are constant, so every averaged frame is the same regardless of resampling.
    assertThat(decoded[0]).isEqualTo((short) 2000);
    assertThat(decoded[8_000]).isEqualTo((short) 2000);
  }

  @Test
  void monoAtTheTargetRateIsPassedThroughUnchanged() throws IOException {
    Path wav = directory.resolve("mono.wav");
    short[] frames = {1, -2, 3, -4, 5};
    Files.write(wav, wav(16_000, 1, frames));

    assertThat(WavDecoder.decodeToMono16kHz(wav)).containsExactly(1, -2, 3, -4, 5);
  }

  /** A listener killed mid-write leaves a header promising more data than the file holds. */
  @Test
  void aTruncatedDataChunkIsReadUpToWhatTheFileActuallyHolds() throws IOException {
    Path wav = directory.resolve("truncated.wav");
    byte[] complete = wav(16_000, 1, new short[] {1, 2, 3, 4, 5, 6, 7, 8});
    Files.write(wav, java.util.Arrays.copyOf(complete, complete.length - 6));

    assertThat(WavDecoder.decodeToMono16kHz(wav)).containsExactly(1, 2, 3, 4, 5);
  }

  @Test
  void anythingThatIsNotSixteenBitPcmIsRejected() throws IOException {
    Path notRiff = directory.resolve("notes.txt");
    Files.writeString(notRiff, "this is not audio at all");
    assertThatThrownBy(() -> WavDecoder.decodeToMono16kHz(notRiff))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("RIFF/WAVE");

    Path eightBit = directory.resolve("eight-bit.wav");
    byte[] header = wav(16_000, 1, new short[] {1, 2});
    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putShort(34, (short) 8);
    Files.write(eightBit, header);
    assertThatThrownBy(() -> WavDecoder.decodeToMono16kHz(eightBit))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("16-bit PCM");
  }

  private static byte[] wav(int sampleRate, int channels, short[] frames) {
    int dataLength = frames.length * 2;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    out.writeBytes(intLe(36 + dataLength));
    out.writeBytes("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    out.writeBytes(intLe(16));
    out.writeBytes(shortLe(1));
    out.writeBytes(shortLe(channels));
    out.writeBytes(intLe(sampleRate));
    out.writeBytes(intLe(sampleRate * channels * 2));
    out.writeBytes(shortLe(channels * 2));
    out.writeBytes(shortLe(16));
    out.writeBytes("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    out.writeBytes(intLe(dataLength));
    for (short frame : frames) {
      out.writeBytes(shortLe(frame));
    }
    return out.toByteArray();
  }

  private static byte[] intLe(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  private static byte[] shortLe(int value) {
    return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value).array();
  }
}
