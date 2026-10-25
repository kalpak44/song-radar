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
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoundCloudTrackSourceTest {

  private HttpServer server;
  private String base;
  private SoundCloudTrackSource source;
  private String siteBody;
  private String assetBody;
  private String searchBody;
  private String aacPlaylistBody;
  private final AtomicInteger searchCalls = new AtomicInteger();
  private final AtomicInteger siteCalls = new AtomicInteger();
  private final AtomicInteger aacStreamCalls = new AtomicInteger();
  private boolean failFirstSearch;
  private boolean failEverySearch;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    siteBody = "<script src=\"" + base + "/assets/app.js\"></script>";
    assetBody = "client_id:\"abcdefghijklmnopqrstuvwxyz12345\"";
    searchBody = collection(track("Get Lucky", mpeg(), "Daft Punk"));
    aacPlaylistBody =
        "#EXTM3U\n#EXT-X-MAP:URI=\""
            + base
            + "/init.mp4\"\n#EXTINF:4,\n"
            + base
            + "/aac-segment.mp4\n#EXT-X-ENDLIST\n";
    server.createContext("/", this::site);
    server.createContext(
        "/assets/app.js", exchange -> respond(exchange, assetBody, "text/javascript"));
    server.createContext("/search/tracks", this::search);
    server.createContext(
        "/stream/mpeg",
        exchange -> respond(exchange, "{\"url\":\"" + base + "/mpeg.m3u8\"}", "application/json"));
    server.createContext("/stream/aac", this::aacStream);
    server.createContext(
        "/stream/aac-noinit",
        exchange ->
            respond(exchange, "{\"url\":\"" + base + "/aac-noinit.m3u8\"}", "application/json"));
    server.createContext(
        "/stream/empty",
        exchange -> respond(exchange, "{\"url\":\"" + base + "/empty.m3u8\"}", "application/json"));
    server.createContext("/stream/nothing", exchange -> respond(exchange, "", "application/json"));
    server.createContext(
        "/mpeg.m3u8",
        exchange ->
            respond(
                exchange,
                "#EXTM3U\n#EXTINF:4,\n"
                    + base
                    + "/seg1.ts\n#EXTINF:4,\n"
                    + base
                    + "/seg2.ts\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl"));
    server.createContext(
        "/aac.m3u8",
        exchange -> respond(exchange, aacPlaylistBody, "application/vnd.apple.mpegurl"));
    server.createContext(
        "/aac-noinit.m3u8",
        exchange ->
            respond(
                exchange,
                "#EXTM3U\n#EXTINF:4,\n" + base + "/init.mp4\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl"));
    server.createContext(
        "/empty.m3u8",
        exchange ->
            respond(exchange, "#EXTM3U\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl"));
    server.createContext(
        "/seg1.ts", exchange -> respond(exchange, new byte[] {1, 2, 3}, "audio/mpeg"));
    server.createContext(
        "/seg2.ts", exchange -> respond(exchange, new byte[] {4, 5}, "audio/mpeg"));
    server.createContext(
        "/init.mp4", exchange -> respond(exchange, new byte[] {1, 2, 3}, "audio/mp4"));
    server.createContext(
        "/aac-segment.mp4", exchange -> respond(exchange, new byte[] {4, 5}, "audio/mp4"));
    server.start();
    source =
        new SoundCloudTrackSource(
            base, base + "/", Pattern.compile(Pattern.quote(base) + "/assets/[^\"]+\\.js"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void prefersTheMpegVariantAndCopiesItsSegmentsThrough() throws IOException {
    searchBody = collection(track("Get Lucky", aac() + "," + mpeg(), "Daft Punk"));

    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    found.content().writeTo(out);

    assertThat(found.source()).isEqualTo("SoundCloud");
    assertThat(found.title()).isEqualTo("Get Lucky");
    assertThat(found.artist()).isEqualTo("Daft Punk");
    assertThat(found.durationMillis()).isEqualTo(240_000);
    assertThat(out.toByteArray()).containsExactly(1, 2, 3, 4, 5);
    assertThat(aacStreamCalls).hasValue(0);
  }

  @Test
  void triesTheAacVariantWhenNoMpegOneIsAdvertised() {
    searchBody = collection(track("Get Lucky", aac(), "Daft Punk"));
    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThatThrownBy(() -> found.content().writeTo(new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no advertised stream");
    assertThat(aacStreamCalls).hasValue(1);
  }

  @Test
  void rejectsAnAacPlaylistWithoutAnInitialisationSegment() {
    searchBody = collection(track("Get Lucky", aacNoInit(), "Daft Punk"));
    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThatThrownBy(() -> found.content().writeTo(new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no advertised stream");
  }

  @Test
  void rejectsAPlaylistWithNoSegments() {
    searchBody =
        collection(
            track("Get Lucky", transcoding("/stream/empty", "mp3", "audio/mpegurl"), "Daft Punk"));
    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThatThrownBy(() -> found.content().writeTo(new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no advertised stream");
  }

  @Test
  void rejectsAStreamResponseWithoutAPlaylist() {
    searchBody =
        collection(
            track(
                "Get Lucky", transcoding("/stream/nothing", "mp3", "audio/mpegurl"), "Daft Punk"));
    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThatThrownBy(() -> found.content().writeTo(new ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no advertised stream");
  }

  @Test
  void rejectsATrackWhoseTranscodingsAreNotHls() {
    searchBody = collection(track("Get Lucky", progressive(), "Daft Punk"));

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void rescrapesTheClientIdOnceWhenTheSearchIsRejected() {
    failFirstSearch = true;

    assertThat(source.find("Daft Punk", "Get Lucky")).isPresent();

    assertThat(searchCalls).hasValue(2);
    assertThat(siteCalls).hasValue(2);
  }

  @Test
  void reusesTheScrapedClientIdAcrossSearches() {
    source.find("Daft Punk", "Get Lucky");
    source.find("Daft Punk", "Get Lucky");

    assertThat(searchCalls).hasValue(2);
    assertThat(siteCalls).hasValue(1);
  }

  @Test
  void returnsEmptyWhenBothSearchAttemptsFail() {
    failEverySearch = true;

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
    assertThat(searchCalls).hasValue(2);
    assertThat(siteCalls).hasValue(2);
  }

  @Test
  void ignoresTranscodingsWithoutAFormat() {
    searchBody = collection(track("Get Lucky", formatless(), "Daft Punk"));

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheSitePublishesNoScripts() {
    siteBody = "<html></html>";

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenNoScriptPublishesAClientId() {
    assetBody = "nothing useful here";

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheSearchResponseHasNoCollection() {
    searchBody = "{}";

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void ignoresResultsWithoutMediaOrAuthorization() {
    searchBody =
        """
        {"collection":[{"title":"Get Lucky","duration":240000,"user":{"username":"Daft Punk"}}]}
        """;

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheBestMatchIsNotCloseEnough() {
    searchBody = collection(track("Karaoke Filler", mpeg(), "Nobody"));

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void usesTheDetectedArtistWhenTheUploadHasNoUser() {
    searchBody = collection(track("Daft Punk Get Lucky", mpeg(), null));

    SourceTrack found = source.find("Daft Punk", "Get Lucky").orElseThrow();

    assertThat(found.artist()).isEqualTo("Daft Punk");
  }

  @Test
  void treatsAnEmptySiteBodyAsNoScripts() {
    siteBody = "";

    assertThat(source.find("Daft Punk", "Get Lucky")).isEmpty();
  }

  @Test
  void namesTheSource() {
    assertThat(source.name()).isEqualTo("SoundCloud");
  }

  private void site(HttpExchange exchange) throws IOException {
    siteCalls.incrementAndGet();
    respond(exchange, siteBody, "text/html");
  }

  private void search(HttpExchange exchange) throws IOException {
    int call = searchCalls.incrementAndGet();
    if (failEverySearch || (failFirstSearch && call == 1)) {
      respond(exchange, 500, "", "text/plain");
      return;
    }
    respond(exchange, searchBody, "application/json");
  }

  private void aacStream(HttpExchange exchange) throws IOException {
    aacStreamCalls.incrementAndGet();
    respond(exchange, "{\"url\":\"" + base + "/aac.m3u8\"}", "application/json");
  }

  private String mpeg() {
    return transcoding("/stream/mpeg", "mp3", "audio/mpegurl");
  }

  private String aac() {
    return transcoding("/stream/aac", "aac_160k", "audio/mp4");
  }

  private String aacNoInit() {
    return transcoding("/stream/aac-noinit", "aac_160k", "audio/mp4");
  }

  private String progressive() {
    return "{\"url\":\""
        + base
        + "/stream/mpeg\",\"preset\":\"mp3\",\"format\":{\"protocol\":\"progressive\","
        + "\"mime_type\":\"audio/mpeg\"}}";
  }

  private String formatless() {
    return "{\"url\":\"" + base + "/stream/mpeg\",\"preset\":\"mp3\"}";
  }

  private String transcoding(String path, String preset, String mimeType) {
    return "{\"url\":\""
        + base
        + path
        + "\",\"preset\":\""
        + preset
        + "\",\"format\":{\"protocol\":\"hls\",\"mime_type\":\""
        + mimeType
        + "\"}}";
  }

  private static String collection(String track) {
    return "{\"collection\":[" + track + "]}";
  }

  private static String track(String title, String transcodings, String user) {
    return "{\"title\":\""
        + title
        + "\",\"duration\":240000,\"track_authorization\":\"tok\",\"media\":{\"transcodings\":["
        + transcodings
        + "]}"
        + (user == null ? "" : ",\"user\":{\"username\":\"" + user + "\"}")
        + "}";
  }

  private static void respond(HttpExchange exchange, String body, String contentType)
      throws IOException {
    respond(exchange, 200, body.getBytes(StandardCharsets.UTF_8), contentType);
  }

  private static void respond(HttpExchange exchange, byte[] body, String contentType)
      throws IOException {
    respond(exchange, 200, body, contentType);
  }

  private static void respond(HttpExchange exchange, int status, String body, String contentType)
      throws IOException {
    respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType);
  }

  private static void respond(HttpExchange exchange, int status, byte[] body, String contentType)
      throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    } else {
      exchange.close();
    }
  }
}
