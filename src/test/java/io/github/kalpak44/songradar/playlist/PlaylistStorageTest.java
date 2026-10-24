package io.github.kalpak44.songradar.playlist;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaylistStorageTest {

  @TempDir Path root;

  @Test
  void storeWritesTheContentAndKeepsTheExtension() throws Exception {
    PlaylistStorage storage = new PlaylistStorage(root.resolve("playlists"));

    String stored = storage.store(stream("#EXTM3U"), "channels.M3U8");

    assertTrue(stored.endsWith(".m3u8"), "the extension should be kept, lowercased");
    assertEquals("#EXTM3U", Files.readString(root.resolve("playlists").resolve(stored)));
  }

  @Test
  void storeIgnoresDirectoriesInTheSuppliedFilename() {
    PlaylistStorage storage = new PlaylistStorage(root);

    String stored = storage.store(stream("x"), "../../etc/passwd.m3u");

    assertFalse(stored.contains("/"), "a stored name must not carry a path");
    assertFalse(stored.contains(".."), "a stored name must not escape the root");
    assertTrue(Files.exists(root.resolve(stored)));
  }

  @Test
  void storeHandlesAFilenameWithNoExtension() {
    PlaylistStorage storage = new PlaylistStorage(root);

    assertFalse(storage.store(stream("x"), "noextension").contains("."));
    assertFalse(storage.store(stream("x"), null).contains("."));
  }

  @Test
  void deleteRemovesTheFileAndToleratesAMissingOne() {
    PlaylistStorage storage = new PlaylistStorage(root);
    String stored = storage.store(stream("x"), "a.m3u");

    storage.delete(stored);

    assertFalse(Files.exists(root.resolve(stored)));
    assertDoesNotThrow(() -> storage.delete(stored), "deleting twice should be harmless");
  }

  private static ByteArrayInputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
}
