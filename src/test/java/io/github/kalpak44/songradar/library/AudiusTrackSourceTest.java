package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudiusTrackSourceTest {

  private HttpServer server;
  private String base;
  private String registryBody;
  private String searchBody;
  private byte[] audio;
  private final AtomicInteger registryRequests = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::api);
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    registryBody = "{\"data\":[\"" + base + "\"]}";
    searchBody =
        """
        {"data":[{"id":"t1","title":"Get Lucky","duration":240,"user":{"name":"Daft Punk"}}]}
        """;
    audio = new byte[] {9, 8, 7, 6};
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void findsATrackThroughTheDiscoveryNodeAndStreamsIt() throws IOException {
    AudiusTrackSource source = new AudiusTrackSource(base);

    SourceTrack track = source.find("Daft Punk", "Get Lucky").orElseThrow();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    track.content().writeTo(out);

    assertThat(track.source()).isEqualTo("Audius");
    assertThat(track.title()).isEqualTo("Get Lucky");
    assertThat(track.artist()).isEqualTo("Daft Punk");
    assertThat(track.durationMillis()).isEqualTo(240_000);
    assertThat(out.toByteArray()).isEqualTo(audio);
  }

  @Test
  void reusesTheDiscoveryNodeAcrossSearches() {
    AudiusTrackSource source = new AudiusTrackSource(base);

    source.find("Daft Punk", "Get Lucky");
    source.find("Daft Punk", "Get Lucky");

    assertThat(registryRequests).hasValue(1);
  }

  @Test
  void fallsBackToTheRegistryUrlWhenItPublishesNoNodes() {
    registryBody = "{\"data\":[]}";

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isPresent();
  }

  @Test
  void returnsEmptyWhenTheSearchHasNoData() {
    searchBody = "{}";

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheSearchResponseIsNull() {
    searchBody = "null";

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void fallsBackToTheRegistryUrlWhenItsResponseIsNull() {
    registryBody = "null";

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isPresent();
  }

  @Test
  void returnsEmptyWhenTheBestMatchIsNotCloseEnough() {
    searchBody =
        """
        {"data":[{"id":"t1","title":"Karaoke Filler","duration":240,"user":{"name":"Nobody"}}]}
        """;

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void ignoresResultsWithoutAnId() {
    searchBody =
        """
        {"data":[{"title":"Get Lucky","duration":240,"user":{"name":"Daft Punk"}}]}
        """;

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void usesTheDetectedArtistWhenTheUploadHasNoUser() {
    searchBody = "{\"data\":[{\"id\":\"t1\",\"title\":\"Daft Punk Get Lucky\",\"duration\":240}]}";

    SourceTrack track = new AudiusTrackSource(base).find("Daft Punk", "Get Lucky").orElseThrow();

    assertThat(track.artist()).isEqualTo("Daft Punk");
  }

  @Test
  void reportsAnEmptyStreamAsAFailure() {
    audio = new byte[0];
    SourceTrack track = new AudiusTrackSource(base).find("Daft Punk", "Get Lucky").orElseThrow();

    assertThatThrownBy(() -> track.content().writeTo(new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no audio");
  }

  @Test
  void returnsEmptyWhenTheRegistryCannotBeReached() {
    server.stop(0);

    assertThat(new AudiusTrackSource(base).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void namesTheSource() {
    assertThat(new AudiusTrackSource(base).name()).isEqualTo("Audius");
  }

  private void api(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (path.equals("/")) {
      registryRequests.incrementAndGet();
      respond(exchange, registryBody, "application/json");
    } else if (path.equals("/v1/tracks/search")) {
      respond(exchange, searchBody, "application/json");
    } else if (path.startsWith("/v1/tracks/") && path.endsWith("/stream")) {
      respond(exchange, audio, "audio/mpeg");
    } else {
      respond(exchange, 404, "", "text/plain");
    }
  }

  private static void respond(HttpExchange exchange, String body, String contentType)
      throws IOException {
    respond(exchange, 200, body, contentType);
  }

  private static void respond(HttpExchange exchange, byte[] body, String contentType)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body, String contentType)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
