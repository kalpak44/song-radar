package io.github.kalpak44.songradar.library;

import de.sciss.jump3r.lowlevel.LameEncoder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import javax.sound.sampled.AudioFormat;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

/**
 * Turns AAC frames into MP3 in this process. The catalogues serve AAC, the deployed image is a JRE
 * with no ffmpeg beside it, and launching one is not an option here.
 */
final class Mp3Encoding {

  private static final int BITRATE_KBPS = 192;

  private Mp3Encoding() {}

  /**
   * Frames arrive from an iterator so a segmented source downloads while it encodes; a whole track
   * decoded to PCM first would be tens of megabytes held for no reason.
   */
  static void encode(byte[] audioSpecificConfig, Iterator<byte[]> frames, OutputStream out)
      throws IOException {
    Decoder decoder = new Decoder(audioSpecificConfig);
    SampleBuffer samples = new SampleBuffer();
    LameEncoder encoder = null;
    byte[] pending = null;
    byte[] encoded = null;
    int filled = 0;

    try {
      while (frames.hasNext()) {
        decoder.decodeFrame(frames.next(), samples);
        byte[] pcm = samples.getData();
        if (pcm.length == 0) {
          continue;
        }
        if (encoder == null) {
          // The sample rate and channel count are only known once a frame has been decoded.
          encoder =
              new LameEncoder(
                  new AudioFormat(samples.getSampleRate(), 16, samples.getChannels(), true, false),
                  BITRATE_KBPS,
                  LameEncoder.CHANNEL_MODE_AUTO,
                  LameEncoder.QUALITY_HIGH,
                  false);
          pending = new byte[encoder.getPCMBufferSize()];
          encoded = new byte[encoder.getMP3BufferSize()];
        }
        int offset = 0;
        while (offset < pcm.length) {
          int take = Math.min(pending.length - filled, pcm.length - offset);
          System.arraycopy(pcm, offset, pending, filled, take);
          filled += take;
          offset += take;
          if (filled == pending.length) {
            out.write(encoded, 0, encoder.encodeBuffer(pending, 0, filled, encoded));
            filled = 0;
          }
        }
      }

      if (encoder == null) {
        throw new IOException("the source returned no decodable audio");
      }
      if (filled > 0) {
        out.write(encoded, 0, encoder.encodeBuffer(pending, 0, filled, encoded));
      }
      out.write(encoded, 0, encoder.encodeFinish(encoded));
    } catch (RuntimeException exception) {
      throw new IOException("the source audio could not be decoded", exception);
    } finally {
      if (encoder != null) {
        encoder.close();
      }
    }
  }
}
