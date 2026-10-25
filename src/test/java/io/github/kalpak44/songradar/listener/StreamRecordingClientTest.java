package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
  void followsMasterPlaylistAndRejectsUndecodableTransportInsteadOfSavingAPlaylistAsAudio()
      throws Exception {
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

  private URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }
}
