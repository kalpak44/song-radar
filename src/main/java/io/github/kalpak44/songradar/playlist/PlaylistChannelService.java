package io.github.kalpak44.songradar.playlist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PlaylistChannelService {

  private static final int MAX_PLAYLIST_BYTES = 16 * 1024 * 1024;
  private static final int TIMEOUT_MILLIS = 10_000;

  private final PlaylistSourceRepository sources;
  private final PlaylistStorage storage;

  public PlaylistChannelService(PlaylistSourceRepository sources, PlaylistStorage storage) {
    this.sources = sources;
    this.storage = storage;
  }

  public List<PlaylistChannel> channels(long playlistId) {
    PlaylistSource source =
        sources
            .findById(playlistId)
            .orElseThrow(() -> new PlaylistUnavailableException("That playlist no longer exists."));
    URI base = source.type() == SourceType.URL ? URI.create(source.url()) : null;
    try (InputStream input = open(source)) {
      byte[] content = input.readNBytes(MAX_PLAYLIST_BYTES + 1);
      if (content.length > MAX_PLAYLIST_BYTES) {
        throw new PlaylistUnavailableException("The playlist is too large to inspect.");
      }
      return M3uParser.parse(new String(content, StandardCharsets.UTF_8), base);
    } catch (IOException | UncheckedIOException e) {
      throw new PlaylistUnavailableException("The playlist could not be loaded.", e);
    }
  }

  public PlaylistChannel requireChannel(long playlistId, String streamUrl) {
    return channels(playlistId).stream()
        .filter(channel -> channel.streamUrl().equals(streamUrl))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Choose a channel from this playlist."));
  }

  private InputStream open(PlaylistSource source) throws IOException {
    if (source.type() == SourceType.FILE) {
      return storage.open(source.storedPath());
    }
    URLConnection connection = URI.create(source.url()).toURL().openConnection();
    connection.setConnectTimeout(TIMEOUT_MILLIS);
    connection.setReadTimeout(TIMEOUT_MILLIS);
    connection.setRequestProperty("User-Agent", "SongRadar/1.0");
    return connection.getInputStream();
  }
}
