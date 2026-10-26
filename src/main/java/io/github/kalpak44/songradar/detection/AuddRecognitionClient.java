package io.github.kalpak44.songradar.detection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

public class AuddRecognitionClient implements RecognitionClient {

  private static final String DEFAULT_ENDPOINT = "https://api.audd.io/";

  private final String token;
  private final String endpoint;
  private final RestClient client;

  public AuddRecognitionClient(String token) {
    this(token, DEFAULT_ENDPOINT);
  }

  /** The endpoint is overridable only so tests can answer in AudD's place. */
  public AuddRecognitionClient(String token, String endpoint) {
    this.token = token == null ? "" : token.trim();
    this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public String name() {
    return "AudD";
  }

  @Override
  public boolean isConfigured() {
    return !token.isBlank();
  }

  @Override
  public String unavailableReason() {
    return "AudD is selected but SONGRADAR_AUDD_API_TOKEN is not set.";
  }

  @Override
  public Map<String, String> recognize(Path file) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("api_token", token);
    body.add("return", "apple_music,spotify");
    body.add("file", new FileSystemResource(file));
    AuddResponse response =
        client
            .post()
            .uri(java.net.URI.create(endpoint))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .body(AuddResponse.class);
    if (response == null || !"success".equalsIgnoreCase(response.status())) {
      throw new IllegalStateException(describe(response));
    }
    if (response.result() == null) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    result.put("title", response.result().title());
    result.put("artist", response.result().artist());
    result.put("album", response.result().album());
    result.put("releaseDate", response.result().release_date());
    result.put("trackUrl", response.result().song_link());
    return result;
  }

  /**
   * AudD answers with HTTP 200 and an error object, so the reason a request failed — an exhausted
   * quota (901/902) reads nothing like a bad token — is only visible if we repeat it. Without this
   * the detection row and the log line say the same opaque thing for every cause.
   */
  private static String describe(AuddResponse response) {
    if (response == null || response.error() == null) {
      return "The recognition provider returned an unsuccessful response.";
    }
    return "The recognition provider returned error %d: %s"
        .formatted(response.error().error_code(), response.error().error_message());
  }

  private record AuddResponse(String status, AuddResult result, AuddError error) {}

  private record AuddError(int error_code, String error_message) {}

  private record AuddResult(
      String title, String artist, String album, String release_date, String song_link) {}
}
