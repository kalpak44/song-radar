package io.github.kalpak44.songradar.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

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
  void listsPlayableFilesRecursivelyForLibraryPlayback() throws IOException {
    Files.createDirectories(root.resolve("library/Listener B"));
    Files.createDirectories(root.resolve("library/Listener A"));
    Files.writeString(root.resolve("library/Listener B/z song.mp3"), "audio");
    Files.writeString(root.resolve("library/Listener A/a song.wav"), "audio");
    Files.writeString(root.resolve("library/Listener A/notes.txt"), "text");
    Files.writeString(root.resolve("library/Listener A/.songradar-download-part.mp3"), "partial");

    List<LibraryFile> tracks = files.listPlayableRecursively("library");

    assertEquals(
        List.of("library/Listener A/a song.wav", "library/Listener B/z song.mp3"),
        tracks.stream().map(LibraryFile::relativePath).toList());
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

  @Test
  void createsFoldersAndRejectsInvalidNames() throws IOException {
    files.createFolder("", "albums");

    assertTrue(Files.isDirectory(root.resolve("albums")));
    assertThrows(IllegalArgumentException.class, () -> files.createFolder("", " "));
    assertThrows(IllegalArgumentException.class, () -> files.createFolder("", ".."));
  }

  @Test
  void uploadsOnlyMp3FilesAndSkipsEmptyUploads() throws IOException {
    MockMultipartFile song =
        new MockMultipartFile("files", "Song.MP3", "audio/mpeg", "music".getBytes());
    MockMultipartFile empty =
        new MockMultipartFile("files", "empty.mp3", "audio/mpeg", new byte[0]);

    files.upload("", Arrays.asList(empty, null, song));

    assertEquals("music", Files.readString(root.resolve("Song.MP3")));
    MockMultipartFile text =
        new MockMultipartFile("files", "notes.txt", "text/plain", "text".getBytes());
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> files.upload("", List.of(text)));
    assertEquals("Only MP3 files can be uploaded here.", failure.getMessage());
  }

  @Test
  void deletesFilesAndFoldersRecursively() throws IOException {
    Path album = Files.createDirectory(root.resolve("albums"));
    Files.writeString(album.resolve("track.mp3"), "music");

    files.delete(List.of("albums"));

    assertFalse(Files.exists(album));
    assertThrows(IllegalArgumentException.class, () -> files.delete(List.of("albums")));
  }

  @Test
  void resolvesFilesAndFoldersWithinTheStorageRoot() throws IOException {
    Path album = Files.createDirectory(root.resolve("albums"));
    Path song = Files.writeString(root.resolve("song.mp3"), "audio");

    assertEquals(album, files.fileOrDirectory("albums"));
    assertEquals(song, files.file("song.mp3"));
    assertEquals(List.of(album, song), files.resolveAll(List.of("albums", "song.mp3")));
    assertThrows(IllegalArgumentException.class, () -> files.fileOrDirectory("song.mp3"));
    assertThrows(IllegalArgumentException.class, () -> files.file("albums"));
    assertThrows(IllegalArgumentException.class, () -> files.resolveAll(List.of()));
    assertThrows(IllegalArgumentException.class, () -> files.resolveAll(null));
    assertThrows(IllegalArgumentException.class, () -> files.file("/etc/passwd"));
  }

  @Test
  void pastesFilesAndRejectsInvalidDestinationsAndSelfCopies() throws IOException {
    Path albums = Files.createDirectory(root.resolve("albums"));
    Files.writeString(albums.resolve("track.mp3"), "music");
    Files.writeString(root.resolve("song.mp3"), "audio");
    Path copies = Files.createDirectory(root.resolve("copies"));

    files.paste(List.of("song.mp3"), "copies");

    assertEquals("audio", Files.readString(copies.resolve("song.mp3")));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of("song.mp3"), "missing"));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of(""), "copies"));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of("albums"), "albums"));
  }

  @Test
  void archivesIndividualFilesAndMultipleSources() throws IOException {
    Path album = Files.createDirectory(root.resolve("albums"));
    Files.writeString(album.resolve("track.mp3"), "music");
    Files.writeString(root.resolve("song.mp3"), "audio");

    Path archive = files.createArchive(List.of("song.mp3", "albums"));

    assertTrue(Files.size(archive) > 0);
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      assertNotNull(zip.getEntry("song.mp3"));
      var track = zip.getEntry("albums/track.mp3");
      assertNotNull(track);
      try (var input = zip.getInputStream(track)) {
        assertEquals("music", new String(input.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void rejectsInvalidArchiveSelections() {
    assertThrows(IllegalArgumentException.class, () -> files.createArchive(List.of()));
    assertThrows(IllegalArgumentException.class, () -> files.createArchive(null));
    assertThrows(IllegalArgumentException.class, () -> files.createArchive(List.of("")));
    assertThrows(IllegalArgumentException.class, () -> files.createArchive(List.of("missing")));
  }

  @Test
  void hidesAndRejectsSymlinks() throws IOException {
    Path outside = Files.createDirectory(root.resolve("outside"));
    Files.createSymbolicLink(root.resolve("linked"), outside);

    assertTrue(files.list("").stream().noneMatch(item -> item.name().equals("linked")));
    assertThrows(IllegalArgumentException.class, () -> files.fileOrDirectory("linked"));
    assertThrows(IllegalArgumentException.class, () -> files.file("linked"));
  }

  @Test
  void recognizesPlayableAudioExtensions() {
    for (String extension : List.of("mp3", "wav", "m4a", "ogg", "flac", "aac")) {
      assertTrue(
          new LibraryFile("track." + extension, "track", false, 1, Instant.EPOCH).playable());
    }
    assertFalse(new LibraryFile("notes.txt", "notes", false, 1, Instant.EPOCH).playable());
    assertFalse(new LibraryFile("folder.mp3", "folder", true, 0, Instant.EPOCH).playable());
  }
}
