package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShazamRecognitionClientTest {

  @TempDir Path directory;

  private HttpServer server;
  private ShazamRecognitionClient client;
  private String responseBody;
  private int responseStatus;
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicReference<String> lastContentLanguage = new AtomicReference<>();
  private final AtomicReference<String> lastUserAgent = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    responseStatus = 200;
    responseBody = "{\"matches\":[]}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          lastPath.set(exchange.getRequestURI().toString());
          lastContentLanguage.set(exchange.getRequestHeaders().getFirst("Content-Language"));
          lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
          lastBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(responseStatus, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    client = new ShazamRecognitionClient("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void itNeedsNoConfigurationToBeUsable() {
    assertThat(client.isConfigured()).isTrue();
    assertThat(client.name()).isEqualTo("Shazam");
  }

  @Test
  void aMatchedTrackIsMappedOntoTheFieldsADetectionStores() throws IOException {
    responseBody =
        """
        {"matches":[{"id":"1"}],
         "track":{"title":"Get Lucky","subtitle":"Daft Punk",
                  "url":"https://www.shazam.com/track/99/get-lucky",
                  "sections":[{"type":"SONG","metadata":[
                      {"title":"Album","text":"Random Access Memories"},
                      {"title":"Released","text":"2013"}]},
                     {"type":"LYRICS","text":["irrelevant"]}]}}
        """;

    var result = client.recognize(recording("match.wav"));

    assertThat(result)
        .containsEntry("title", "Get Lucky")
        .containsEntry("artist", "Daft Punk")
        .containsEntry("album", "Random Access Memories")
        .containsEntry("releaseDate", "2013")
        .containsEntry("trackUrl", "https://www.shazam.com/track/99/get-lucky");
  }

  @Test
  void aTrackWithoutAlbumOrReleaseStillYieldsTitleAndArtist() throws IOException {
    responseBody = "{\"track\":{\"title\":\"Untitled\",\"subtitle\":\"Unknown\"}}";

    var result = client.recognize(recording("sparse.wav"));

    assertThat(result).containsEntry("title", "Untitled").containsEntry("artist", "Unknown");
    assertThat(result.get("album")).isNull();
  }

  @Test
  void anUnrecognisedRecordingIsAnEmptyResultRatherThanAFailure() throws IOException {
    responseBody = "{\"matches\":[],\"tagid\":\"abc\"}";

    assertThat(client.recognize(recording("nomatch.wav"))).isEmpty();
  }

  @Test
  void aThrottledAddressIsReportedAsItsOwnCause() throws IOException {
    responseStatus = 429;
    responseBody = "{}";

    assertThatThrownBy(() -> client.recognize(recording("limited.wav")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("rate-limited");
  }

  @Test
  void theRequestCarriesTheFingerprintAndNoAudio() throws IOException {
    client.recognize(recording("request.wav"));

    assertThat(lastPath.get())
        .startsWith("/discovery/v5/en/US/android/-/tag/")
        .contains("shazamapiversion=v3");
    assertThat(lastContentLanguage.get()).isEqualTo("en_US");
    assertThat(lastUserAgent.get()).startsWith("Dalvik/");
    assertThat(lastBody.get())
        .contains("data:audio/vnd.shazam.sig;base64,")
        .contains("\"samplems\":12000")
        .contains("\"timezone\":\"Europe/Paris\"");
  }

  /** A silent capture cannot be fingerprinted, and asking anyway only spends the rate limit. */
  @Test
  void aRecordingWithNoPeaksIsNotSentAtAll() throws IOException {
    Path silent = directory.resolve("silence.wav");
    Files.write(silent, wav(new short[16_000 * 12]));

    assertThat(client.recognize(silent)).isEmpty();
    assertThat(calls.get()).isZero();
  }

  private Path recording(String name) throws IOException {
    short[] samples = new short[16_000 * 12];
    for (int index = 0; index < samples.length; index++) {
      if ((index / 3000) % 2 != 0) {
        continue;
      }
      double value =
          Math.sin(2 * Math.PI * 400 * index / 16_000.0)
              + Math.sin(2 * Math.PI * 2500 * index / 16_000.0)
              + Math.sin(2 * Math.PI * 4200 * index / 16_000.0);
      samples[index] = (short) (4000 * value);
    }
    Path wav = directory.resolve(name);
    Files.write(wav, wav(samples));
    return wav;
  }

  private static byte[] wav(short[] samples) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(intLe(36 + samples.length * 2));
    out.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(intLe(16));
    out.writeBytes(shortLe(1));
    out.writeBytes(shortLe(1));
    out.writeBytes(intLe(16_000));
    out.writeBytes(intLe(32_000));
    out.writeBytes(shortLe(2));
    out.writeBytes(shortLe(16));
    out.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(intLe(samples.length * 2));
    for (short sample : samples) {
      out.writeBytes(shortLe(sample));
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
