package io.github.kalpak44.songradar.playlist;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A registered playlist. Exactly one of {@code url} and {@code storedPath} is set, which the
 * table's own check constraint also enforces.
 */
@Table("playlist_sources")
public record PlaylistSource(
    @Id Long id, String name, SourceType type, String url, String storedPath, Instant addedAt) {

  public static PlaylistSource ofUrl(String name, String url, Instant addedAt) {
    return new PlaylistSource(null, name, SourceType.URL, url, null, addedAt);
  }

  public static PlaylistSource ofFile(String name, String storedPath, Instant addedAt) {
    return new PlaylistSource(null, name, SourceType.FILE, null, storedPath, addedAt);
  }
}
