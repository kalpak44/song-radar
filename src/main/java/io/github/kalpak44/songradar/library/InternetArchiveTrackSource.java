package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Last in the chain. Its catalogue is mostly not current music, so it rarely answers — but it
 * serves MP3 outright, needs no credential, and holds older recordings the others do not.
 */
@Component
@Order(5)
public class InternetArchiveTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(InternetArchiveTrackSource.class);

  private static final String SEARCH = "https://archive.org/advancedsearch.php";
  private static final String METADATA = "https://archive.org/metadata/";
  private static final String DOWNLOAD = "https://archive.org/download/";

  private final RestClient client;

  public InternetArchiveTrackSource() {
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
    return "Internet Archive";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      // Fielded rather than free text: a bare query matches the description and the full text of
      // scanned books, which answers a song title with anything that ever mentioned it.
      // Creator is left out: it is frequently absent, or spelled "Beethoven, Ludwig van" where
      // the detection says "Beethoven". The candidates are scored on title and creator below,
      // which rejects the wrong item without the query having to.
      String query = "title:(" + quote(title) + ") AND mediatype:(audio)";
      // A String is treated as a template and encoded again, which turns the already-encoded
      // field list into a parameter name the search rejects outright. The URI goes through as is.
      SearchResponse response =
          client
              .get()
              .uri(
                  SEARCH + "?q={q}&fl[]=identifier&fl[]=title&fl[]=creator&rows=5&output=json",
                  query)
              .retrieve()
              .body(SearchResponse.class);
      if (response == null || response.response() == null || response.response().docs() == null) {
        return Optional.empty();
      }
      return response.response().docs().stream()
          .filter(doc -> doc.identifier() != null)
          .max(Comparator.comparingDouble(doc -> score(doc, artist, title)))
          .filter(doc -> score(doc, artist, title) >= TrackMatching.MINIMUM_SCORE)
          .flatMap(doc -> stream(doc, artist, title));
    } catch (RuntimeException exception) {
      log.info(
          "Internet Archive could not serve \"{} - {}\": {}", artist, title, exception.toString());
      return Optional.empty();
    }
  }

  private Optional<SourceTrack> stream(Doc doc, String artist, String title) {
    Metadata metadata =
        client.get().uri(URI.create(METADATA + doc.identifier())).retrieve().body(Metadata.class);
    if (metadata == null || metadata.files() == null) {
      return Optional.empty();
    }
    // An item is often a whole concert or album, so the file whose own name matches the track is
    // the one worth taking; falling back to the first MP3 would file the wrong song. Scored on
    // the title alone, because a file inside an artist's item is rarely named after the artist.
    Optional<FileEntry> best =
        metadata.files().stream()
            .filter(
                file ->
                    file.name() != null && file.name().toLowerCase(Locale.ROOT).endsWith(".mp3"))
            .max(Comparator.comparingDouble(file -> TrackMatching.score(file.name(), "", title)))
            .filter(
                file -> TrackMatching.score(file.name(), "", title) >= TrackMatching.MINIMUM_SCORE);
    if (best.isEmpty()) {
      return Optional.empty();
    }
    String url =
        DOWNLOAD
            + doc.identifier()
            + "/"
            + URLEncoder.encode(best.get().name(), StandardCharsets.UTF_8).replace("+", "%20");
    return Optional.of(
        new SourceTrack(
            name(),
            title,
            doc.creator() == null ? artist : doc.creator(),
            seconds(best.get().length()),
            out -> {
              byte[] audio = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
              if (audio == null || audio.length == 0) {
                throw new IOException("the track returned no audio");
              }
              out.write(audio);
            }));
  }

  /** Lengths arrive as seconds or as mm:ss depending on how the item was derived. */
  private static long seconds(String length) {
    if (length == null || length.isBlank()) {
      return 0;
    }
    try {
      if (!length.contains(":")) {
        return Math.round(Double.parseDouble(length) * 1000);
      }
      String[] parts = length.split(":");
      double total = 0;
      for (String part : parts) {
        total = total * 60 + Double.parseDouble(part);
      }
      return Math.round(total * 1000);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  /** The query language treats these as syntax, and a title may legitimately contain them. */
  private static String quote(String value) {
    return value == null ? "" : value.replaceAll("[\"():\\[\\]{}^~*?\\\\]", " ").trim();
  }

  private static double score(Doc doc, String artist, String title) {
    String creator = doc.creator() == null ? "" : doc.creator();
    return TrackMatching.score(doc.title() + " " + creator, artist, title);
  }

  record SearchResponse(Response response) {}

  record Response(List<Doc> docs) {}

  record Doc(String identifier, String title, String creator) {}

  record Metadata(List<FileEntry> files) {}

  record FileEntry(String name, String format, String length) {}
}
