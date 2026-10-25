package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JioSaavnTrackSourceTest {

  private HttpServer server;
  private String api;
  private String searchBody;
  private String detailsBody;
  private boolean authReturnsNothing;
  private boolean authOmitsUrl;
  private final AtomicReference<String> authorisedPath = new AtomicReference<>();

  @BeforeEach
  void start() throws IOException {
    searchBody =
        """
        {"songs":{"data":[{"id":"abc123","title":"Get Lucky","description":"Daft Punk"}]}}
        """;
    detailsBody =
        """
        {"abc123":{"song":"Get Lucky","primary_artists":"Daft Punk","duration":"240",
                   "encrypted_media_path":"enc/path+value"}}
        """;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api.php", this::api);
    server.start();
    api = "http://127.0.0.1:" + server.getAddress().getPort() + "/api.php";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void findsTheBestMatchAndAuthorisesItsMediaUrl() {
    JioSaavnTrackSource source = new JioSaavnTrackSource(api);

    SourceTrack track = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThat(track.source()).isEqualTo("JioSaavn");
    assertThat(track.title()).isEqualTo("Get Lucky");
    assertThat(track.artist()).isEqualTo("Daft Punk");
    assertThat(track.durationMillis()).isEqualTo(240_000);
    assertThat(authorisedPath.get()).isEqualTo("enc/path+value");
  }

  @Test
  void swapsTheSignedMp3PathForTheMp4TheCdnActuallyServes() {
    JioSaavnTrackSource source = new JioSaavnTrackSource(api);

    String media =
        source.mediaUrl(
            new JioSaavnTrackSource.Details("Get Lucky", "Daft Punk", "240", "enc/path+value"));

    assertThat(media)
        .isEqualTo(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/audio/track_320.mp4?sig=x");
  }

  @Test
  void rejectsATrackWithoutAMediaPath() {
    JioSaavnTrackSource source = new JioSaavnTrackSource(api);

    assertThatThrownBy(
            () ->
                source.mediaUrl(
                    new JioSaavnTrackSource.Details("Get Lucky", "Daft Punk", "240", " ")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no media path");
  }

  @Test
  void rejectsAnAuthorisationResponseWithoutAUrl() {
    authReturnsNothing = true;
    JioSaavnTrackSource source = new JioSaavnTrackSource(api);

    assertThatThrownBy(
            () ->
                source.mediaUrl(
                    new JioSaavnTrackSource.Details("Get Lucky", "Daft Punk", "240", "enc/path")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be authorised");
  }

  @Test
  void returnsEmptyWhenTheCatalogueHasNoSongs() {
    searchBody = "{\"songs\":{\"data\":[]}}";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheSearchResponseHasNoSongsSection() {
    searchBody = "{}";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheSearchResponseHasANullSongsSection() {
    searchBody = "{\"songs\":null}";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheDetailsResponseIsNull() {
    detailsBody = "null";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void rejectsAnAuthorisationResponseThatOmitsTheUrl() {
    authOmitsUrl = true;
    JioSaavnTrackSource source = new JioSaavnTrackSource(api);

    assertThatThrownBy(
            () ->
                source.mediaUrl(
                    new JioSaavnTrackSource.Details("Get Lucky", "Daft Punk", "240", "enc/path")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be authorised");
  }

  @Test
  void returnsEmptyWhenTheBestMatchIsNotCloseEnough() {
    searchBody =
        """
        {"songs":{"data":[{"id":"abc123","title":"Karaoke Filler","description":"Nobody"}]}}
        """;

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheDetailsHaveNoEntryForTheSong() {
    detailsBody = "{}";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheApiAnswersWithSomethingOtherThanJson() {
    searchBody = "<html>no</html>";

    assertThat(new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void treatsABlankDurationAsUnknown() {
    detailsBody =
        """
        {"abc123":{"song":"Get Lucky","primary_artists":"Daft Punk","duration":"",
                   "encrypted_media_path":"enc/path"}}
        """;

    SourceTrack track = new JioSaavnTrackSource(api).find("Daft Punk", "Get Lucky").orElseThrow();

    assertThat(track.durationMillis()).isZero();
  }

  @Test
  void namesTheSource() {
    assertThat(new JioSaavnTrackSource(api).name()).isEqualTo("JioSaavn");
  }

  private void api(HttpExchange exchange) throws IOException {
    Map<String, String> query = query(exchange);
    String call = query.getOrDefault("__call", "");
    switch (call) {
      case "autocomplete.get" -> respond(exchange, searchBody);
      case "song.getDetails" -> respond(exchange, detailsBody);
      case "song.generateAuthToken" -> {
        authorisedPath.set(query.get("url"));
        if (authOmitsUrl) {
          respond(exchange, "{\"type\":\"mp3\",\"status\":\"success\"}");
        } else {
          String authUrl =
              authReturnsNothing
                  ? ""
                  : "http://127.0.0.1:"
                      + server.getAddress().getPort()
                      + "/audio/track_320.mp3?sig=x";
          respond(
              exchange,
              "{\"auth_url\":\"" + authUrl + "\",\"type\":\"mp3\",\"status\":\"success\"}");
        }
      }
      default -> respond(exchange, 404, "");
    }
  }

  private static Map<String, String> query(HttpExchange exchange) {
    Map<String, String> values = new HashMap<>();
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) {
      return values;
    }
    for (String pair : raw.split("&")) {
      int split = pair.indexOf('=');
      String name = split < 0 ? pair : pair.substring(0, split);
      String value = split < 0 ? "" : pair.substring(split + 1);
      values.put(
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    respond(exchange, 200, body);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/html");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
