package io.github.kalpak44.songradar.playlist;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stores uploaded playlists under a configured root directory. */
@Component
public class PlaylistStorage {

  private final Path root;

  public PlaylistStorage(@Value("${songradar.storage.playlists}") Path root) {
    this.root = root;
  }

  /**
   * Writes the upload under a generated name and returns its path relative to the root.
   *
   * <p>The caller's filename is never used on disk: it arrives from the browser and can contain
   * path separators, so honouring it would let an upload choose where it lands. The original name
   * is kept in the database instead, where it is only ever displayed.
   */
  public String store(InputStream content, String originalFilename) {
    String storedName = UUID.randomUUID() + extensionOf(originalFilename);
    try {
      Files.createDirectories(root);
      Files.copy(content, root.resolve(storedName));
    } catch (IOException e) {
      throw new UncheckedIOException("could not store the uploaded playlist", e);
    }
    return storedName;
  }

  /** Removes a stored upload. A row whose file is already gone still deletes cleanly. */
  public void delete(String storedPath) {
    try {
      Files.deleteIfExists(root.resolve(storedPath));
    } catch (IOException e) {
      throw new UncheckedIOException("could not delete the stored playlist", e);
    }
  }

  private static String extensionOf(String filename) {
    if (filename == null) {
      return "";
    }
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot).toLowerCase();
  }
}
