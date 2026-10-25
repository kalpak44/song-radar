package io.github.kalpak44.songradar.library;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Searches SoundCloud and serves the match as MP3. Audio arrives as an HLS playlist: its MPEG
 * variant is copied through, and its AAC variant is transcoded.
 */
@Component
@Order(1)
public class SoundCloudTrackSource implements TrackSource {

  private static final Logger log = LoggerFactory.getLogger(SoundCloudTrackSource.class);

  private static final String API = "https://api-v2.soundcloud.com";
  private static final String BROWSER_AGENT = "Mozilla/5.0";
  private static final Pattern ASSET =
      Pattern.compile("https://a-v2\\.sndcdn\\.com/assets/[^\"]+\\.js");
  private static final Pattern CLIENT_ID = Pattern.compile("client_id[:=]\"([A-Za-z0-9]{25,})\"");

  /**
   * AAC presets this decoder reproduces faithfully. Everything else SoundCloud offers is HE-AAC.
   */
  private static final List<String> LOSSLESSLY_DECODED = List.of("aac_160k", "aac_256k");

  private final RestClient client;
  private volatile String clientId;

  public SoundCloudTrackSource() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(10));
    requestFactory.setReadTimeout(Duration.ofSeconds(60));
    this.client =
        RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader("User-Agent", BROWSER_AGENT)
            .build();
  }

  @Override
  public String name() {
    return "SoundCloud";
  }

  @Override
  public Optional<SourceTrack> find(String artist, String title) {
    try {
      return search(artist, title, true);
    } catch (RuntimeException exception) {
      log.info("SoundCloud could not serve \"{} - {}\": {}", artist, title, exception.toString());
      return Optional.empty();
    }
  }

  private Optional<SourceTrack> search(String artist, String title, boolean mayRetry) {
    String id = clientId();
    if (id == null) {
      return Optional.empty();
    }
    String query = URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
    SearchResponse response;
    try {
      response =
          client
              .get()
              .uri(API + "/search/tracks?q=" + query + "&limit=10&client_id=" + id)
              .retrieve()
              .body(SearchResponse.class);
    } catch (RuntimeException exception) {
      // The scraped id is rotated rather than revoked, so one re-scrape separates an expired
      // id from SoundCloud being unreachable.
      if (mayRetry) {
        clientId = null;
        return search(artist, title, false);
      }
      throw exception;
    }
    if (response == null || response.collection() == null) {
      return Optional.empty();
    }
    return response.collection().stream()
        .filter(track -> track.media() != null && track.track_authorization() != null)
        // An upload offering nothing but HE-AAC is passed over here rather than ranked last,
        // so a lower-scoring upload that can be transcoded faithfully still gets its turn.
        .filter(track -> !usable(track).isEmpty())
        .max(java.util.Comparator.comparingDouble(track -> score(track, artist, title)))
        .filter(track -> score(track, artist, title) >= TrackMatching.MINIMUM_SCORE)
        .map(track -> toSourceTrack(track, artist, id));
  }

  private SourceTrack toSourceTrack(Track track, String artist, String id) {
    return new SourceTrack(
        name(),
        track.title(),
        track.user() == null ? artist : track.user().username(),
        track.duration(),
        out -> write(track, id, out));
  }

  private void write(Track track, String id, OutputStream out) throws IOException {
    for (Transcoding transcoding : usable(track)) {
      try {
        writeTranscoding(transcoding, track.track_authorization(), id, out);
        return;
      } catch (IOException | RuntimeException exception) {
        log.info(
            "SoundCloud would not serve the {} stream: {}",
            transcoding.format().mime_type(),
            exception.toString());
      }
    }
    throw new IOException("no advertised stream could be fetched");
  }

  /**
   * The variants worth fetching, MPEG first because it is copied through rather than decoded.
   * HE-AAC is excluded outright: this decoder does not reconstruct its high band, and the result is
   * audible noise at the right length, which no check on the file can catch. An upload with nothing
   * else is left to another source — no file beats a bad one.
   *
   * <p>An upload can also advertise a variant it will not serve, which arrives as a 404 on the
   * stream rather than as a missing transcoding, so the survivors are still tried in turn.
   */
  private static List<Transcoding> usable(Track track) {
    return track.media().transcodings().stream()
        .filter(t -> t.format() != null && "hls".equals(t.format().protocol()))
        .filter(t -> isMpeg(t) || LOSSLESSLY_DECODED.contains(t.preset()))
        .sorted(java.util.Comparator.comparingInt(t -> isMpeg(t) ? 0 : 1))
        .toList();
  }

  /** MPEG playlists come labelled both audio/mpeg and audio/mpegurl. */
  private static boolean isMpeg(Transcoding transcoding) {
    String mime = transcoding.format().mime_type();
    return mime != null && mime.startsWith("audio/mpeg");
  }

  private void writeTranscoding(
      Transcoding transcoding, String authorization, String id, OutputStream out)
      throws IOException {
    String playlistUrl =
        Optional.ofNullable(
                client
                    .get()
                    .uri(
                        transcoding.url()
                            + "?client_id="
                            + id
                            + "&track_authorization="
                            + URLEncoder.encode(authorization, StandardCharsets.UTF_8))
                    .retrieve()
                    .body(StreamResponse.class))
            .map(StreamResponse::url)
            .orElseThrow(() -> new IOException("the stream has no playlist"));

    String playlist = text(playlistUrl);
    List<String> segments = new ArrayList<>();
    String initSegment = null;
    for (String line : playlist.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("#EXT-X-MAP:URI=\"")) {
        initSegment = trimmed.substring(trimmed.indexOf('"') + 1, trimmed.lastIndexOf('"'));
      } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        segments.add(trimmed);
      }
    }
    if (segments.isEmpty()) {
      throw new IOException("the playlist listed no audio segments");
    }

    // The playlist decides, not the advertised type: segments with no initialisation
    // segment in front of them are MPEG audio, and concatenating them is the whole job.
    if (initSegment == null) {
      if (!isMpeg(transcoding)) {
        throw new IOException("the AAC playlist has no initialisation segment");
      }
      for (String segment : segments) {
        out.write(bytes(segment));
      }
      return;
    }
    Mp3Encoding.encode(
        Mp4Aac.audioSpecificConfig(bytes(initSegment)), new FragmentFrames(segments), out);
  }

  /** Fetches one fragment at a time, so a four-minute track is never held whole in memory. */
  private final class FragmentFrames implements Iterator<byte[]> {
    private final Deque<String> remaining;
    private final Deque<byte[]> frames = new ArrayDeque<>();

    private FragmentFrames(List<String> segments) {
      this.remaining = new ArrayDeque<>(segments);
    }

    @Override
    public boolean hasNext() {
      while (frames.isEmpty() && !remaining.isEmpty()) {
        frames.addAll(Mp4Aac.fragmentFrames(bytes(remaining.removeFirst())));
      }
      return !frames.isEmpty();
    }

    @Override
    public byte[] next() {
      if (!hasNext()) {
        throw new java.util.NoSuchElementException();
      }
      return frames.removeFirst();
    }
  }

  private byte[] bytes(String url) {
    byte[] body = client.get().uri(url).retrieve().body(byte[].class);
    return body == null ? new byte[0] : body;
  }

  private String text(String url) {
    String body = client.get().uri(url).retrieve().body(String.class);
    return body == null ? "" : body;
  }

  /** Scraped from the site bundle: SoundCloud publishes no other way to get one. */
  private String clientId() {
    String cached = clientId;
    if (cached != null) {
      return cached;
    }
    Matcher assets = ASSET.matcher(text("https://soundcloud.com/"));
    List<String> scripts = new ArrayList<>();
    while (assets.find()) {
      scripts.add(assets.group());
    }
    // The id sits in one of the last bundles, so searching from the end costs fewer requests.
    for (int i = scripts.size() - 1; i >= 0; i--) {
      Matcher found = CLIENT_ID.matcher(text(scripts.get(i)));
      if (found.find()) {
        clientId = found.group(1);
        return clientId;
      }
    }
    log.info("SoundCloud published no usable client id");
    return null;
  }

  /** How much of "artist title" the upload's own title and uploader account for. */
  private static double score(Track track, String artist, String title) {
    String uploader = track.user() == null ? "" : track.user().username();
    return TrackMatching.score(track.title() + " " + uploader, artist, title);
  }

  record SearchResponse(List<Track> collection) {}

  record Track(String title, long duration, String track_authorization, Media media, User user) {}

  record Media(List<Transcoding> transcodings) {}

  record Transcoding(String url, String preset, Format format) {}

  record Format(String protocol, String mime_type) {}

  record User(String username) {}

  record StreamResponse(String url) {}
}
