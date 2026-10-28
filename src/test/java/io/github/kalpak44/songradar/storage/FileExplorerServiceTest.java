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
  void movesDirectoriesAndCreatesZipArchivesWithTheirFolderStructure() throws IOException {
    Path album = Files.createDirectory(root.resolve("albums"));
    Files.writeString(album.resolve("track.mp3"), "music");
    Files.createDirectory(root.resolve("moved"));
    files.paste(List.of("albums"), "moved");
    Path archived = files.createArchive(List.of("moved"));

    assertTrue(Files.exists(root.resolve("moved/albums/track.mp3")));
    assertFalse(Files.exists(root.resolve("albums")));
    assertTrue(Files.size(archived) > 0);
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
  void movesFilesAndRejectsInvalidDestinationsAndSelfMoves() throws IOException {
    Path albums = Files.createDirectory(root.resolve("albums"));
    Files.writeString(albums.resolve("track.mp3"), "music");
    Files.writeString(root.resolve("song.mp3"), "audio");
    Path moved = Files.createDirectory(root.resolve("moved"));

    files.paste(List.of("song.mp3"), "moved");

    assertEquals("audio", Files.readString(moved.resolve("song.mp3")));
    assertFalse(Files.exists(root.resolve("song.mp3")));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of("albums"), "missing"));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of(""), "moved"));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of("albums"), "albums"));
    assertThrows(
        IllegalArgumentException.class, () -> files.paste(List.of("moved/song.mp3"), "moved"));
    assertThrows(IllegalArgumentException.class, () -> files.paste(List.of("gone.mp3"), "moved"));
  }

  /** A name already taken at the destination is replaced, which is what makes a paste a tidy-up. */
  @Test
  void pastingOverAnExistingNameReplacesIt() throws IOException {
    Path listener = Files.createDirectory(root.resolve("listener"));
    Files.writeString(listener.resolve("track.mp3"), "new");
    Path library = Files.createDirectory(root.resolve("library"));
    Files.writeString(library.resolve("track.mp3"), "old");

    files.paste(List.of("listener/track.mp3"), "library");

    assertEquals("new", Files.readString(library.resolve("track.mp3")));
    assertFalse(Files.exists(listener.resolve("track.mp3")));
  }

  /**
   * Two folders of the same name merge rather than one wiping the other: a listener folder moved
   * onto one already there keeps the tracks only the destination had.
   */
  @Test
  void pastingAFolderOntoOneOfTheSameNameMergesThem() throws IOException {
    Path incoming = Files.createDirectories(root.resolve("incoming/Radio"));
    Files.writeString(incoming.resolve("shared.mp3"), "new");
    Files.writeString(incoming.resolve("only-incoming.mp3"), "new");
    Path existing = Files.createDirectories(root.resolve("library/Radio"));
    Files.writeString(existing.resolve("shared.mp3"), "old");
    Files.writeString(existing.resolve("only-existing.mp3"), "old");

    files.paste(List.of("incoming/Radio"), "library");

    assertEquals("new", Files.readString(existing.resolve("shared.mp3")));
    assertEquals("old", Files.readString(existing.resolve("only-existing.mp3")));
    assertTrue(Files.exists(existing.resolve("only-incoming.mp3")));
    assertFalse(Files.exists(root.resolve("incoming/Radio")));
  }

  /** A folder landing on a name a file holds replaces it, rather than refusing or merging. */
  @Test
  void pastingAFolderOverAFileReplacesTheFile() throws IOException {
    Path folder = Files.createDirectories(root.resolve("incoming/track.mp3"));
    Files.writeString(folder.resolve("inside.mp3"), "inside");
    Path library = Files.createDirectory(root.resolve("library"));
    Files.writeString(library.resolve("track.mp3"), "old");

    files.paste(List.of("incoming/track.mp3"), "library");

    assertTrue(Files.isDirectory(library.resolve("track.mp3")));
    assertEquals("inside", Files.readString(library.resolve("track.mp3/inside.mp3")));
  }

  /** And the other way round: the folder that was there goes, contents and all. */
  @Test
  void pastingAFileOverAFolderReplacesTheFolder() throws IOException {
    Files.createDirectory(root.resolve("incoming"));
    Files.writeString(root.resolve("incoming/track.mp3"), "flat");
    Path occupied = Files.createDirectories(root.resolve("library/track.mp3"));
    Files.writeString(occupied.resolve("inside.mp3"), "inside");

    files.paste(List.of("incoming/track.mp3"), "library");

    assertTrue(Files.isRegularFile(root.resolve("library/track.mp3")));
    assertEquals("flat", Files.readString(root.resolve("library/track.mp3")));
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
