package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The second place a detected track is looked for. Its catalogue is strongest where SoundCloud is
 * weakest, and it serves one progressive AAC file rather than a playlist.
 */
@Component
@Order(2)
public class JioSaavnTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(JioSaavnTrackSource.class);

  private static final String API = "https://www.jiosaavn.com/api.php";
  private static final String SEARCH =
      "?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=";
  private static final String DETAILS =
      "?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=";

  /**
   * The site hands out a signed, ready-to-use media URL when asked with the encrypted path. Doing
   * that server-side keeps the vendor's legacy cipher out of this application entirely.
   */
  private static final String AUTH =
      "?__call=song.generateAuthToken&bitrate=320&api_version=4&_format=json"
          + "&ctx=web6dot0&xcountry=IN&_marker=0&url=";

  /**
   * The API labels its JSON responses text/html, so no message converter will touch them and the
   * bodies have to be read as text and parsed here.
   */
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final String apiBase;
  private final RestClient client;

  public JioSaavnTrackSource() {
    this(API);
  }

  JioSaavnTrackSource(String apiBase) {
    this.apiBase = apiBase;
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    this.client =
        RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader("User-Agent", "Mozilla/5.0")
            .build();
  }

  @Override
  public String name() {
    return "JioSaavn";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      AutoComplete response = JSON.readValue(text(search(artist, title)), AutoComplete.class);
      if (response == null || response.songs() == null || response.songs().data() == null) {
        return Optional.empty();
      }
      return response.songs().data().stream()
          .max(Comparator.comparingDouble(song -> score(song, artist, title)))
          .filter(song -> score(song, artist, title) >= TrackMatching.MINIMUM_SCORE)
          .flatMap(song -> details(song.id()))
          .map(this::toSourceTrack);
    } catch (RuntimeException exception) {
      log.info("JioSaavn could not serve \"{} - {}\": {}", artist, title, exception.toString());
      return Optional.empty();
    }
  }

  private Optional<Details> details(String id) {
    Map<String, Details> response =
        JSON.readValue(text(detailsUrl(id)), new TypeReference<Map<String, Details>>() {});
    return response == null ? Optional.empty() : Optional.ofNullable(response.get(id));
  }

  private String search(String artist, String title) {
    String query = URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
    return apiBase + SEARCH + query;
  }

  private String detailsUrl(String id) {
    return apiBase + DETAILS + id;
  }

  private String text(String url) {
    String body = client.get().uri(URI.create(url)).retrieve().body(String.class);
    return body == null ? "" : body;
  }

  private SourceTrack toSourceTrack(Details details) {
    String media = mediaUrl(details);
    long duration =
        details.duration() == null || details.duration().isBlank()
            ? 0
            : Long.parseLong(details.duration().trim()) * 1000;
    return new SourceTrack(
        name(),
        details.song(),
        details.primary_artists(),
        duration,
        out -> {
          byte[] file = client.get().uri(URI.create(media)).retrieve().body(byte[].class);
          if (file == null || file.length == 0) {
            throw new IOException("the track returned no audio");
          }
          Mp3Encoding.encode(
              Mp4Aac.audioSpecificConfig(file), Mp4Aac.progressiveFrames(file).iterator(), out);
        });
  }

  /**
   * The token endpoint signs an {@code _320.mp3} path while the CDN serves this catalogue's audio
   * as AAC in an {@code _320.mp4} container under the same signature. Asking the site to resolve
   * the encrypted path avoids implementing its DES scheme here.
   */
  String mediaUrl(Details details) {
    if (details.encrypted_media_path() == null || details.encrypted_media_path().isBlank()) {
      throw new IllegalStateException("the track has no media path");
    }
    String encoded = URLEncoder.encode(details.encrypted_media_path(), StandardCharsets.UTF_8);
    AuthToken token = JSON.readValue(text(apiBase + AUTH + encoded), AuthToken.class);
    if (token == null || token.auth_url() == null || token.auth_url().isBlank()) {
      throw new IllegalStateException("the track's media URL could not be authorised");
    }
    return token.auth_url().replace("_320.mp3", "_320.mp4");
  }

  private static double score(Song song, String artist, String title) {
    return TrackMatching.score(song.title() + " " + song.description(), artist, title);
  }

  record AutoComplete(Songs songs) {}

  record Songs(List<Song> data) {}

  record Song(String id, String title, String description) {}

  record Details(
      String song, String primary_artists, String duration, String encrypted_media_path) {}

  record AuthToken(String auth_url) {}
}
