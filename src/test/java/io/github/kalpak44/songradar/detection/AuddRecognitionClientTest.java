package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuddRecognitionClientTest {

  @TempDir Path directory;

  private HttpServer server;
  private AuddRecognitionClient client;
  private String responseBody;

  @BeforeEach
  void start() throws IOException {
    responseBody = "{\"status\":\"success\",\"result\":null}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    client =
        new AuddRecognitionClient(
            "token", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aConfiguredTokenEnablesRecognition() {
    assertThat(new AuddRecognitionClient(" token ").isConfigured()).isTrue();
    assertThat(new AuddRecognitionClient(" token ").name()).isEqualTo("AudD");
  }

  @Test
  void aMissingOrBlankTokenDisablesRecognitionAndSaysWhy() {
    assertThat(new AuddRecognitionClient(null).isConfigured()).isFalse();
    assertThat(new AuddRecognitionClient("   ").isConfigured()).isFalse();
    assertThat(new AuddRecognitionClient(null).unavailableReason())
        .contains("SONGRADAR_AUDD_API_TOKEN");
  }

  @Test
  void aMatchedTrackIsMappedOntoTheFieldsADetectionStores() throws IOException {
    responseBody =
        """
        {"status":"success","result":{"title":"Get Lucky","artist":"Daft Punk",
         "album":"Random Access Memories","release_date":"2013-04-19",
         "song_link":"https://lis.tn/GetLucky"}}
        """;

    assertThat(client.recognize(recording()))
        .containsEntry("title", "Get Lucky")
        .containsEntry("artist", "Daft Punk")
        .containsEntry("album", "Random Access Memories")
        .containsEntry("releaseDate", "2013-04-19")
        .containsEntry("trackUrl", "https://lis.tn/GetLucky");
  }

  @Test
  void anUnrecognisedRecordingIsAnEmptyResultRatherThanAFailure() throws IOException {
    responseBody = "{\"status\":\"success\",\"result\":null}";

    assertThat(client.recognize(recording())).isEmpty();
  }

  /**
   * AudD answers HTTP 200 with an error object, and an exhausted quota reads nothing like a
   * rejected token — repeating its code and message is the difference between a diagnosable failure
   * and an opaque one.
   */
  @Test
  void anExhaustedQuotaIsReportedWithTheCodeAndMessageAuddGave() throws IOException {
    responseBody =
        """
        {"status":"error","error":{"error_code":902,
         "error_message":"Recognition failed: authorization failed: the limit was reached."}}
        """;

    assertThatThrownBy(() -> client.recognize(recording()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("902")
        .hasMessageContaining("the limit was reached");
  }

  @Test
  void anErrorWithoutDetailsStillFails() throws IOException {
    responseBody = "{\"status\":\"error\"}";

    assertThatThrownBy(() -> client.recognize(recording()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unsuccessful response");
  }

  private Path recording() throws IOException {
    Path wav = directory.resolve("capture.wav");
    Files.write(wav, new byte[] {1, 2, 3, 4});
    return wav;
  }
}
