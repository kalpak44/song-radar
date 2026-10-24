package io.github.kalpak44.songradar.listener;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
  void capturesHttpAudioAndChoosesAnExtensionFromItsContentType() throws Exception {
    byte[] audio = {1, 2, 3, 4};
    server.createContext(
        "/stream",
        exchange -> {
          exchange.getResponseHeaders().add("Content-Type", "audio/mpeg");
          exchange.sendResponseHeaders(200, audio.length);
          exchange.getResponseBody().write(audio);
          exchange.close();
        });
    server.start();

    Path file =
        client.capture(uri("/stream"), root.resolve("recordings"), "sample", Duration.ofSeconds(1));

    assertTrue(file.getFileName().toString().endsWith(".mp3"));
    assertArrayEquals(audio, Files.readAllBytes(file));
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
  void emptyResponseIsReportedAndItsPartialFileIsRemoved() throws Exception {
    server.createContext(
        "/empty",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();

    assertThrows(
        IOException.class,
        () -> client.capture(uri("/empty"), root, "empty", Duration.ofSeconds(1)));
    assertTrue(Files.notExists(root.resolve("empty.part")));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + server.getAddress().getPort() + path);
  }
}
