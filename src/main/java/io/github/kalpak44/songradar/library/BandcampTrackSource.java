package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serves MP3 with no transcoding, which is why it is worth having despite a catalogue that leans
 * towards remixes and reworks — the matcher is what keeps those out.
 */
@Component
@Order(4)
public class BandcampTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(BandcampTrackSource.class);

  private static final String SEARCH =
      "https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic";

  /** The track page carries its own player data in this attribute, HTML-escaped. */
  private static final Pattern TRALBUM = Pattern.compile("data-tralbum=\"([^\"]+)\"");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final RestClient client;

  public BandcampTrackSource() {
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
    return "Bandcamp";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      SearchResponse response =
          client
              .post()
              .uri(URI.create(SEARCH))
              .contentType(MediaType.APPLICATION_JSON)
              .body(new SearchRequest((artist + " " + title).trim(), "t", false, null))
              .retrieve()
              .body(SearchResponse.class);
      if (response == null || response.auto() == null || response.auto().results() == null) {
        return Optional.empty();
      }
      return response.auto().results().stream()
          .filter(result -> "t".equals(result.type()) && result.item_url_path() != null)
          .max(Comparator.comparingDouble(result -> score(result, artist, title)))
          .filter(result -> score(result, artist, title) >= TrackMatching.MINIMUM_SCORE)
          .flatMap(result -> stream(result, artist));
    } catch (RuntimeException exception) {
      log.info("Bandcamp could not serve \"{} - {}\": {}", artist, title, exception.toString());
      return Optional.empty();
    }
  }

  /** The stream URL lives on the track page, not in the search result. */
  private Optional<SourceTrack> stream(Result result, String artist) {
    String page =
        client.get().uri(URI.create(result.item_url_path())).retrieve().body(String.class);
    Matcher found = TRALBUM.matcher(page == null ? "" : page);
    if (!found.find()) {
      return Optional.empty();
    }
    TrackPage data = JSON.readValue(unescape(found.group(1)), new TypeReference<TrackPage>() {});
    if (data.trackinfo() == null || data.trackinfo().isEmpty()) {
      return Optional.empty();
    }
    Info info = data.trackinfo().getFirst();
    String url = info.file() == null ? null : info.file().get("mp3-128");
    if (url == null) {
      return Optional.empty();
    }
    String absolute = url.startsWith("//") ? "https:" + url : url;
    return Optional.of(
        new SourceTrack(
            name(),
            result.name(),
            result.band_name() == null ? artist : result.band_name(),
            Math.round(info.duration() * 1000),
            out -> {
              byte[] audio = client.get().uri(URI.create(absolute)).retrieve().body(byte[].class);
              if (audio == null || audio.length == 0) {
                throw new IOException("the track returned no audio");
              }
              out.write(audio);
            }));
  }

  /** The attribute is HTML-escaped in the page source; only these five appear in it. */
  private static String unescape(String value) {
    return value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&");
  }

  private static double score(Result result, String artist, String title) {
    String band = result.band_name() == null ? "" : result.band_name();
    return TrackMatching.score(result.name() + " " + band, artist, title);
  }

  record SearchRequest(String search_text, String search_filter, boolean full_page, Long fan_id) {}

  record SearchResponse(Auto auto) {}

  record Auto(List<Result> results) {}

  record Result(String type, String name, String band_name, String item_url_path) {}

  record TrackPage(List<Info> trackinfo) {}

  record Info(String title, double duration, Map<String, String> file) {}
}
