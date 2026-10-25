package io.github.kalpak44.songradar.detection;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class AuddRecognitionClient {

  private final String token;
  private final RestClient client;

  public AuddRecognitionClient(@Value("${songradar.recognition.audd.api-token:}") String token) {
    this.token = token == null ? "" : token.trim();
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  public boolean isConfigured() {
    return !token.isBlank();
  }

  public Map<String, String> recognize(Path file) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("api_token", token);
    body.add("return", "apple_music,spotify");
    body.add("file", new FileSystemResource(file));
    AuddResponse response =
        client
            .post()
            .uri("https://api.audd.io/")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(body)
            .retrieve()
            .body(AuddResponse.class);
    if (response == null || !"success".equalsIgnoreCase(response.status())) {
      throw new IllegalStateException(
          "The recognition provider returned an unsuccessful response.");
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

  private record AuddResponse(String status, AuddResult result) {}

  private record AuddResult(
      String title, String artist, String album, String release_date, String song_link) {}
}
