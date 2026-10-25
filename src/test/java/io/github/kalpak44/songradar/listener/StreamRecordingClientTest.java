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
    client = new StreamRecordingClient();
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
    assertEquals(
        wav.length - 44, ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    assertTrue(Files.notExists(directory.resolve("sample.ts.part")));
    assertTrue(Files.notExists(directory.resolve("sample.wav.part")));
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

  private static byte[] transportWithSilentAacFrames() {
    ByteArrayOutputStream transport = new ByteArrayOutputStream();
    writePacket(transport, 0, true, programAssociationTable());
    writePacket(transport, 0x100, true, programMapTable());
    ByteArrayOutputStream audio = new ByteArrayOutputStream();
    for (int index = 0; index < 16; index++) {
      audio.writeBytes(silentAacFrame());
    }
    writePacket(transport, 0x101, false, audio.toByteArray());
    return transport.toByteArray();
  }

  private static void writePacket(
      ByteArrayOutputStream transport, int pid, boolean pointerField, byte[] payload) {
    byte[] packet = new byte[188];
    Arrays.fill(packet, (byte) 0xFF);
    packet[0] = 0x47;
    packet[1] = (byte) (0x40 | ((pid >> 8) & 0x1F));
    packet[2] = (byte) (pid & 0xFF);
    packet[3] = 0x10;
    int offset = 4;
    if (pointerField) {
      packet[offset++] = 0;
    }
    System.arraycopy(payload, 0, packet, offset, Math.min(payload.length, packet.length - offset));
    transport.writeBytes(packet);
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

  /** A valid 44.1 kHz mono AAC-LC ADTS frame whose single SCE carries no spectral data. */
  private static byte[] silentAacFrame() {
    return new byte[] {
      (byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0x01, 0x7F, (byte) 0xFC, 0, 0, 0, 7
    };
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }
}
