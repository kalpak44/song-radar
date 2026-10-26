package io.github.kalpak44.songradar.detection;

import io.github.kalpak44.songradar.detection.shazam.ShazamSignature;
import io.github.kalpak44.songradar.detection.shazam.ShazamSignatureGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Recognises a recording against Shazam.
 *
 * <p>Shazam publishes no API. This talks to the endpoint its Android app uses, so there is no
 * contract behind it: the route, the fields and the limits can change without notice, and a change
 * shows up as an unparseable response rather than a documented error. In exchange it needs no
 * credentials and no quota, and it uploads only the fingerprint — the spectral peaks — never the
 * recording.
 *
 * <p>Requests are throttled by Shazam per source address. One capture every few minutes sits well
 * inside what that tolerates, but a burst does not, so a refusal is reported as its own message.
 */
public class ShazamRecognitionClient implements RecognitionClient {

  private static final Logger log = LoggerFactory.getLogger(ShazamRecognitionClient.class);

  private static final String DEFAULT_ENDPOINT = "https://amp.shazam.com";
  private static final String TIMEZONE = "Europe/Paris";

  /**
   * The app identifies as a handful of old Android builds. Varying it per request mirrors the
   * client we are impersonating; a single fixed agent is the more obvious thing to rate-limit.
   */
  private static final List<String> USER_AGENTS =
      List.of(
          "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
          "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
          "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
          "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
          "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G928F Build/MMB29K)",
          "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-J500FN Build/LMY48B)",
          "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
          "Dalvik/1.6.0 (Linux; U; Android 4.4.4; SM-G360H Build/KTU84P)");

  private final String endpoint;
  private final RestClient client;

  public ShazamRecognitionClient() {
    this(DEFAULT_ENDPOINT);
  }

  /** The endpoint is overridable only so tests can answer in Shazam's place. */
  public ShazamRecognitionClient(String endpoint) {
    this.endpoint =
        endpoint == null || endpoint.isBlank()
            ? DEFAULT_ENDPOINT
            : endpoint.trim().replaceAll("/+$", "");
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    this.client = RestClient.builder().requestFactory(requestFactory).build();
  }

  @Override
  public String name() {
    return "Shazam";
  }

  /** Nothing to configure — there is no account and no token behind this provider. */
  @Override
  public boolean isConfigured() {
    return true;
  }

  @Override
  public String unavailableReason() {
    return "Shazam needs no configuration.";
  }

  @Override
  public Map<String, String> recognize(Path file) {
    ShazamSignature signature;
    try {
      signature = ShazamSignatureGenerator.fromRecording(file);
    } catch (IOException exception) {
      throw new UncheckedIOException("The recording could not be fingerprinted.", exception);
    }
    if (signature.peakCount() == 0) {
      // Silence, or a capture too quiet to peak-pick. Shazam would answer "no match" anyway.
      log.debug("No spectral peaks in {}, skipping the Shazam request", file.getFileName());
      return Map.of();
    }

    long timestamp = System.currentTimeMillis() & 0xffffffffL;
    ShazamResponse response =
        client
            .post()
            .uri(URI.create(tagUrl()))
            .contentType(MediaType.APPLICATION_JSON)
            .header("Content-Language", "en_US")
            .header(
                "User-Agent",
                USER_AGENTS.get(ThreadLocalRandom.current().nextInt(USER_AGENTS.size())))
            .body(
                new TagRequest(
                    new Geolocation(300, 45, 2),
                    new Signature(signature.sampleMillis(), timestamp, signature.encodeToUri()),
                    timestamp,
                    TIMEZONE))
            .retrieve()
            .onStatus(
                status -> status.value() == 429,
                (request, failure) -> {
                  throw new IllegalStateException(
                      "The recognition provider rate-limited this address.");
                })
            .body(ShazamResponse.class);

    if (response == null) {
      throw new IllegalStateException("The recognition provider returned an empty response.");
    }
    if (response.track() == null) {
      return Map.of();
    }

    Map<String, String> result = new LinkedHashMap<>();
    result.put("title", response.track().title());
    result.put("artist", response.track().subtitle());
    result.put("album", response.track().metadata("Album"));
    result.put("releaseDate", response.track().metadata("Released"));
    result.put("trackUrl", response.track().url());
    return result;
  }

  /**
   * Each recognition is a fresh "tag" and carries two identifiers of its own, as the app sends a
   * new pair per request rather than a stable device id.
   */
  private String tagUrl() {
    return "%s/discovery/v5/en/US/android/-/tag/%s/%s?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"
        .formatted(
            endpoint,
            UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            UUID.randomUUID().toString());
  }

  private record TagRequest(
      Geolocation geolocation, Signature signature, long timestamp, String timezone) {}

  private record Geolocation(int altitude, int latitude, int longitude) {}

  private record Signature(int samplems, long timestamp, String uri) {}

  private record ShazamResponse(Track track) {}

  private record Track(String title, String subtitle, String url, List<Section> sections) {

    /** Album and release year arrive as free-form label/value pairs in the song section. */
    String metadata(String label) {
      if (sections == null) {
        return null;
      }
      return sections.stream()
          .filter(section -> "SONG".equals(section.type()) && section.metadata() != null)
          .flatMap(section -> section.metadata().stream())
          .filter(entry -> label.equals(entry.title()))
          .map(Metadatum::text)
          .findFirst()
          .orElse(null);
    }
  }

  private record Section(String type, List<Metadatum> metadata) {}

  private record Metadatum(String title, String text) {}
}
