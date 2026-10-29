package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamRecordingClientTest {

  @TempDir Path root;

  private HttpServer server;
  private StreamRecordingClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    client = new StreamRecordingClient("ffmpeg");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void followsMasterPlaylistAndRejectsUndecodableTransportInsteadOfSavingAPlaylistAsAudio() {
    byte[] invalidTransport = {1, 2, 3, 4};
    server.createContext(
        "/master.m3u8",
        exchange -> {
          byte[] body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\nmedia/list.m3u8\n".getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/media/list.m3u8",
        exchange -> {
          byte[] body = "#EXTM3U\n#EXTINF:4.0,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/vnd.apple.mpegurl");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/media/segment.ts",
        exchange -> {
          assertTrue(exchange.getRequestURI().getQuery().contains("token=abc"));
          exchange.sendResponseHeaders(200, invalidTransport.length);
          exchange.getResponseBody().write(invalidTransport);
          exchange.close();
        });
    server.start();

    assertThrows(
        IOException.class,
        () ->
            client.capture(
                URI.create(uri("/master.m3u8").toString() + "?token=abc"),
                root,
                "sample",
                Duration.ofSeconds(1)));
    assertTrue(Files.notExists(root.resolve("sample.wav")));
    assertTrue(Files.notExists(root.resolve("sample.wav.part")));
    assertTrue(Files.notExists(root.resolve("sample.ts.part")));
  }

  @Test
  void rejectsNonHttpStreamsWithoutCreatingAFile() {
    assertThrows(
        IOException.class,
        () ->
            client.capture(
                URI.create("file:///tmp/audio.mp3"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsNonPositiveDurationsBeforeCallingTheStream() {
    assertThrows(
        IOException.class,
        () ->
            client.capture(
                URI.create("https://example.com/list.m3u8"), root, "sample", Duration.ZERO));
    assertTrue(Files.notExists(root.resolve("sample.ts.part")));
  }

  @Test
  void capturesMpegTsAacIntoAPlayableWav() throws Exception {
    byte[] transport = transportWithSilentAacFrames();
    server.createContext(
        "/media.m3u8",
        exchange -> {
          byte[] body = "#EXTM3U\n#EXTINF:4.0,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/segment.ts",
        exchange -> {
          exchange.sendResponseHeaders(200, transport.length);
          exchange.getResponseBody().write(transport);
          exchange.close();
        });
    server.start();

    Path directory = root.resolve("capture");
    StreamRecordingClient.Capture capture =
        client.capture(uri("/media.m3u8"), directory, "sample", Duration.ofSeconds(1));

    assertTrue(capture.durationMillis() >= 250);
    assertEquals(directory.resolve("sample.wav"), capture.path());
    byte[] wav = Files.readAllBytes(capture.path());
    assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
    assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
    // The chunks are walked rather than read at fixed offsets: a decoder is free to put its own
    // chunks in front of the audio, and a header-shaped assumption would break on one that does.
    assertEquals(wav.length - 8, littleEndianInt(wav, 4));
    assertTrue(dataChunkLength(wav) > 0);
    assertTrue(Files.notExists(directory.resolve("sample.ts.part")));
    assertTrue(Files.notExists(directory.resolve("sample.wav.part")));
  }

  private static int littleEndianInt(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static int dataChunkLength(byte[] wav) {
    int cursor = 12;
    while (cursor + 8 <= wav.length) {
      int length = littleEndianInt(wav, cursor + 4);
      if ("data".equals(new String(wav, cursor, 4, StandardCharsets.US_ASCII))) {
        assertTrue(cursor + 8 + length <= wav.length, "the data chunk runs past the file");
        return length;
      }
      cursor += 8 + length + (length & 1);
    }
    return 0;
  }

  /**
   * The point of the whole decode path: audio that went in comes out, all of it. The decoder this
   * replaced answered frames it could not handle with a buffer of zeros and no error, so a capture
   * was the right length with silence punched through it — 10% of the frames of a tone like this
   * one, and 42% of a 96 kbps music stream. Nothing but the samples themselves says so.
   */
  @Test
  void decodesEveryFrameRatherThanBlankingTheOnesItCannotHandle() throws Exception {
    byte[] transport;
    try (var resource = getClass().getResourceAsStream("/listener/tone-96k.ts")) {
      transport = resource.readAllBytes();
    }
    server.createContext(
        "/media.m3u8",
        exchange -> {
          byte[] body = "#EXTM3U\n#EXTINF:3.0,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes();
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/segment.ts",
        exchange -> {
          exchange.sendResponseHeaders(200, transport.length);
          exchange.getResponseBody().write(transport);
          exchange.close();
        });
    server.start();

    StreamRecordingClient.Capture capture =
        client.capture(uri("/media.m3u8"), root.resolve("tone"), "tone", Duration.ofSeconds(3));

    assertTrue(capture.durationMillis() >= 2900, "decoded only " + capture.durationMillis() + "ms");
    byte[] wav = Files.readAllBytes(capture.path());
    int silent = silentFrames(wav);
    assertEquals(0, silent, silent + " frames of the tone came back as digital silence");
  }

  /** Counts 1024-sample frames — the unit an AAC decoder works in — that are entirely zero. */
  private static int silentFrames(byte[] wav) {
    int cursor = 12;
    int data = -1;
    int length = 0;
    while (cursor + 8 <= wav.length) {
      int size = littleEndianInt(wav, cursor + 4);
      if ("data".equals(new String(wav, cursor, 4, StandardCharsets.US_ASCII))) {
        data = cursor + 8;
        length = Math.min(size, wav.length - data);
        break;
      }
      cursor += 8 + size + (size & 1);
    }
    assertTrue(data > 0, "the recording has no data chunk");
    int bytesPerFrame = 1024 * 2 * 2;
    int silent = 0;
    for (int frame = 0; (frame + 1) * bytesPerFrame <= length; frame++) {
      boolean zero = true;
      for (int index = 0; index < bytesPerFrame; index++) {
        if (wav[data + frame * bytesPerFrame + index] != 0) {
          zero = false;
          break;
        }
      }
      if (zero) {
        silent++;
      }
    }
    return silent;
  }

  @Test
  void rejectsAPlaylistThatIsNotHls() {
    serve("/plain.m3u8", 200, "not a playlist".getBytes());
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/plain.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsAnHlsPlaylistWithNoSegments() {
    serve("/empty.m3u8", 200, "#EXTM3U\n#EXT-X-ENDLIST\n".getBytes());
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/empty.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsPlaylistsThatNestVariantsTooDeeply() {
    serve("/loop.m3u8", 200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nloop.m3u8\n".getBytes());
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/loop.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsEncryptedPlaylists() {
    serve(
        "/encrypted.m3u8",
        200,
        "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\nsegment.ts\n".getBytes());
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/encrypted.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsPlaylistsReturnedWithAnHttpError() {
    serve("/missing.m3u8", 404, new byte[0]);
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/missing.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsSegmentsOutsideHttp() {
    serve("/ftp.m3u8", 200, "#EXTM3U\n#EXTINF:4,\nftp://example.com/segment.ts\n".getBytes());
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/ftp.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  @Test
  void rejectsSegmentDownloadsThatFail() {
    serve("/failed-media.m3u8", 200, "#EXTM3U\n#EXTINF:4,\nmissing.ts\n".getBytes());
    serve("/missing.ts", 404, new byte[0]);
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/failed-media.m3u8"), root, "sample", Duration.ofSeconds(1)));
  }

  private void serve(String path, int status, byte[] body) {
    server.createContext(
        path,
        exchange -> {
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
  }

  /**
   * A transport stream a real demuxer accepts, which the hand-rolled one this used to be decoded by
   * did not have to be: the audio is carried in PES packets, and each one is sized to fill whole
   * transport packets so the 0xFF padding at the end of the last one is never read as audio.
   */
  private static byte[] transportWithSilentAacFrames() {
    ByteArrayOutputStream transport = new ByteArrayOutputStream();
    writeSection(transport, 0, programAssociationTable());
    writeSection(transport, 0x100, programMapTable());
    int continuity = 0;
    for (int group = 0; group < PES_PACKETS; group++) {
      ByteArrayOutputStream audio = new ByteArrayOutputStream();
      for (int index = 0; index < FRAMES_PER_PES; index++) {
        audio.writeBytes(silentAacFrame());
      }
      byte[] pes = pesPacket(audio.toByteArray(), group);
      continuity = writePayload(transport, 0x101, pes, continuity, false);
    }
    return transport.toByteArray();
  }

  /**
   * {@code FRAMES_PER_PES} is the smallest count for which the PES packet is a whole number of
   * transport payloads: 14 header bytes plus 11 per frame has to divide by 184.
   */
  private static final int FRAMES_PER_PES = 166;

  private static final int PES_PACKETS = 4;

  private static byte[] pesPacket(byte[] payload, int group) {
    long presentation = 10_000 + (long) group * FRAMES_PER_PES * 1024 * 90_000 / 44_100;
    ByteArrayOutputStream pes = new ByteArrayOutputStream();
    pes.writeBytes(new byte[] {0, 0, 1, (byte) 0xC0});
    int length = payload.length + 8;
    pes.write((length >> 8) & 0xFF);
    pes.write(length & 0xFF);
    pes.writeBytes(new byte[] {(byte) 0x80, (byte) 0x80, 5});
    pes.write((int) (0x21 | ((presentation >> 29) & 0x0E)));
    pes.write((int) ((presentation >> 22) & 0xFF));
    pes.write((int) (0x01 | ((presentation >> 14) & 0xFE)));
    pes.write((int) ((presentation >> 7) & 0xFF));
    pes.write((int) (0x01 | ((presentation << 1) & 0xFE)));
    pes.writeBytes(payload);
    return pes.toByteArray();
  }

  private static void writeSection(ByteArrayOutputStream transport, int pid, byte[] section) {
    writePayload(transport, pid, section, 0, true);
  }

  /** Returns the next continuity counter, which a demuxer checks for gaps. */
  private static int writePayload(
      ByteArrayOutputStream transport, int pid, byte[] payload, int continuity, boolean pointer) {
    int offset = 0;
    boolean first = true;
    while (offset < payload.length) {
      byte[] packet = new byte[188];
      Arrays.fill(packet, (byte) 0xFF);
      packet[0] = 0x47;
      packet[1] = (byte) ((first ? 0x40 : 0) | ((pid >> 8) & 0x1F));
      packet[2] = (byte) (pid & 0xFF);
      packet[3] = (byte) (0x10 | (continuity & 0x0F));
      int start = 4;
      if (pointer && first) {
        packet[start++] = 0;
      }
      int length = Math.min(payload.length - offset, packet.length - start);
      System.arraycopy(payload, offset, packet, start, length);
      transport.writeBytes(packet);
      offset += length;
      continuity = (continuity + 1) & 0x0F;
      first = false;
    }
    return continuity;
  }

  private static byte[] programAssociationTable() {
    byte[] section = new byte[16];
    section[1] = (byte) 0xB0;
    section[2] = 13;
    section[4] = 1;
    section[5] = (byte) 0xC1;
    section[9] = 1;
    section[10] = (byte) 0xE1;
    return section;
  }

  private static byte[] programMapTable() {
    byte[] section = new byte[21];
    section[0] = 2;
    section[1] = (byte) 0xB0;
    section[2] = 18;
    section[4] = 1;
    section[5] = (byte) 0xC1;
    section[8] = (byte) 0xE1;
    section[9] = 1;
    section[12] = 15;
    section[13] = (byte) 0xE1;
    section[14] = 1;
    return section;
  }

  /** A valid 44.1 kHz stereo AAC-LC ADTS frame whose elements carry no spectral data. */
  private static byte[] silentAacFrame() {
    return new byte[] {
      (byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0x01, 0x7F, (byte) 0xFC, 0, 0, 0, 7
    };
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }
}
