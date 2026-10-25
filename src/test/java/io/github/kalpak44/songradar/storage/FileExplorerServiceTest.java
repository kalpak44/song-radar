package io.github.kalpak44.songradar.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileExplorerServiceTest {
  @TempDir Path root;
  private FileExplorerService files;

  @BeforeEach
  void setUp() {
    files = new FileExplorerService(root);
  }

  @Test
  void listsFoldersBeforeFilesAndDoesNotExposeSymlinks() throws IOException {
    Files.createDirectory(root.resolve("albums"));
    Files.writeString(root.resolve("song.mp3"), "audio");

    List<LibraryFile> listed = files.list("");

    assertEquals("albums", listed.getFirst().name());
    assertTrue(listed.get(1).playable());
  }

  @Test
  void rejectsPathTraversalAndPreventsDeletingRoot() {
    assertThrows(IllegalArgumentException.class, () -> files.list("../outside"));
    assertThrows(IllegalArgumentException.class, () -> files.delete(List.of("")));
  }

  @Test
  void copiesDirectoriesAndCreatesZipArchivesWithTheirFolderStructure() throws IOException {
    Path album = Files.createDirectory(root.resolve("albums"));
    Files.writeString(album.resolve("track.mp3"), "music");
    Files.createDirectory(root.resolve("copies"));
    files.paste(List.of("albums"), "copies");
    Path copied = files.createArchive(List.of("albums"));

    assertTrue(Files.exists(root.resolve("copies/albums/track.mp3")));
    assertTrue(Files.size(copied) > 0);
    assertFalse(
        files.list("").stream().anyMatch(item -> item.name().startsWith(".songradar-download-")));
  }
}
