package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tried first: the only source here with a documented public API, and the only one that serves MP3
 * already encoded. Its catalogue is narrower than the others, so a miss here is routine.
 */
@Component
@Order(1)
public class AudiusTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(AudiusTrackSource.class);

  /** Returns the discovery nodes currently serving the API. */
  private static final String REGISTRY = "https://api.audius.co";

  private static final String APP = "songradar";

  private final RestClient client;
  private final String registryUrl;
  private volatile String host;

  public AudiusTrackSource() {
    this(REGISTRY);
  }

  AudiusTrackSource(String registryUrl) {
    this.registryUrl = registryUrl;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    this.client =
        RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader("User-Agent", "SongRadar")
            .build();
  }

  @Override
  public String name() {
    return "Audius";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      String node = host();
      SearchResponse response =
          client
              .get()
              .uri(
                  node + "/v1/tracks/search?app_name={app}&query={q}",
                  APP,
                  (artist + " " + title).trim())
              .retrieve()
              .body(SearchResponse.class);
      if (response == null || response.data() == null) {
        return Optional.empty();
      }
      return response.data().stream()
          .filter(track -> track.id() != null)
          .max(Comparator.comparingDouble(track -> score(track, artist, title)))
          .filter(track -> score(track, artist, title) >= TrackMatching.MINIMUM_SCORE)
          .map(track -> toSourceTrack(node, track, artist));
    } catch (RuntimeException exception) {
      log.info("Audius could not serve \"{} - {}\": {}", artist, title, exception.toString());
      return Optional.empty();
    }
  }

  private SourceTrack toSourceTrack(String node, Track track, String artist) {
    String stream = node + "/v1/tracks/" + track.id() + "/stream?app_name=" + APP;
    return new SourceTrack(
        name(),
        track.title(),
        track.user() == null ? artist : track.user().name(),
        track.duration() * 1000L,
        out -> {
          // Already MP3, so the bytes go straight out: nothing here needs decoding.
          byte[] audio = client.get().uri(stream).retrieve().body(byte[].class);
          if (audio == null || audio.length == 0) {
            throw new IOException("the track returned no audio");
          }
          out.write(audio);
        });
  }

  /** One node can be out of rotation, so the registry decides rather than a hard-coded host. */
  private String host() {
    String cached = host;
    if (cached != null) {
      return cached;
    }
    Registry registryResponse = client.get().uri(registryUrl).retrieve().body(Registry.class);
    host =
        registryResponse == null
                || registryResponse.data() == null
                || registryResponse.data().isEmpty()
            ? registryUrl
            : registryResponse.data().getFirst();
    return host;
  }

  private static double score(Track track, String artist, String title) {
    String uploader = track.user() == null ? "" : track.user().name();
    return TrackMatching.score(track.title() + " " + uploader, artist, title);
  }

  record Registry(List<String> data) {}

  record SearchResponse(List<Track> data) {}

  record Track(String id, String title, int duration, User user) {}

  record User(String name) {}
}
