package io.github.kalpak44.songradar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.storage.FileExplorerService;
import io.github.kalpak44.songradar.storage.LibraryFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileExplorerController.class)
class FileExplorerControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private FileExplorerService files;

  @TempDir Path root;

  @BeforeEach
  void setUp() throws Exception {
    when(files.root()).thenReturn(root);
    when(files.listPlayableRecursively("library")).thenReturn(List.of());
  }

  @Test
  void libraryPageShowsOnlyTheLibraryScope() throws Exception {
    Path folder = root.resolve("library/sub");
    when(files.fileOrDirectory("library/sub")).thenReturn(folder);
    when(files.relative(any())).thenReturn("library/sub");
    when(files.list("library/sub")).thenReturn(List.of());

    mockMvc
        .perform(get("/library").param("path", "sub"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard"))
        .andExpect(model().attribute("activeNav", "library"))
        .andExpect(model().attribute("scope", "library"))
        .andExpect(model().attribute("currentPath", "sub"))
        .andExpect(model().attribute("clipboardCount", 0))
        .andExpect(
            model().attribute("storageRoot", root.resolve("library").normalize().toString()));

    assertThat(Files.isDirectory(root.resolve("library"))).isTrue();
  }

  @Test
  void musicLibraryShowsPlayAllAcrossListenerFolders() throws Exception {
    when(files.relative(any())).thenReturn("library");
    when(files.list("library"))
        .thenReturn(
            List.of(new LibraryFile("Listener", "library/Listener", true, 0, Instant.EPOCH)));
    when(files.listPlayableRecursively("library"))
        .thenReturn(
            List.of(
                new LibraryFile(
                    "song.mp3", "library/Listener/song.mp3", false, 100, Instant.EPOCH)));

    mockMvc
        .perform(get("/library"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("playableCount", 1))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("Play all")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("library/Listener/song.mp3")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("play-previous")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("play-next")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("play-shuffle")));
  }

  @Test
  void filesPageShowsTheWholeStorage() throws Exception {
    Path folder = root.resolve("sub");
    when(files.fileOrDirectory("sub")).thenReturn(folder);
    when(files.relative(any())).thenReturn("sub");
    when(files.list("sub")).thenReturn(List.of());

    mockMvc
        .perform(get("/files").param("path", "sub"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("activeNav", "dashboard"))
        .andExpect(model().attribute("scope", "all"))
        .andExpect(model().attribute("currentPath", "sub"))
        .andExpect(model().attribute("storageRoot", root.normalize().toString()));
  }

  @Test
  void rootPageStartsAtTheStorageRoot() throws Exception {
    when(files.relative(any())).thenReturn("");
    when(files.list("")).thenReturn(List.of());

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("currentPath", ""))
        .andExpect(model().attribute("scope", "library"));
  }

  @Test
  void createsAFolderAndRedirectsToItsParent() throws Exception {
    mockMvc
        .perform(
            post("/files/folder")
                .param("path", "albums")
                .param("scope", "all")
                .param("name", "New"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/?path=albums"))
        .andExpect(flash().attribute("successMessage", "Folder created."));

    verify(files).createFolder("albums", "New");
  }

  @Test
  void reportsAFolderFailureWithTheGenericMessage() throws Exception {
    doThrow(new IOException("disk full")).when(files).createFolder("", "New");

    mockMvc
        .perform(post("/files/folder").param("path", "").param("scope", "all").param("name", "New"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            flash().attribute("errorMessage", "That file operation could not be completed."));
  }

  @Test
  void rejectsAPathThatEscapesTheLibrary() throws Exception {
    mockMvc
        .perform(
            post("/files/folder")
                .param("path", "../outside")
                .param("scope", "library")
                .param("name", "New"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/library?path=..%2Foutside"))
        .andExpect(flash().attribute("errorMessage", "Invalid library path."));
  }

  @Test
  void uploadsFilesInTheSelectedScope() throws Exception {
    MockMultipartFile upload =
        new MockMultipartFile("files", "song.mp3", "audio/mpeg", "music".getBytes());

    mockMvc
        .perform(multipart("/files/upload").file(upload).param("scope", "library"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/library"))
        .andExpect(flash().attribute("successMessage", "Files uploaded."));

    verify(files).upload(eq("library"), anyList());
  }

  @Test
  void reportsAnUploadRejectionWithItsMessage() throws Exception {
    doThrow(new IllegalArgumentException("Only MP3 files can be uploaded here."))
        .when(files)
        .upload(anyString(), anyList());
    MockMultipartFile upload =
        new MockMultipartFile("files", "notes.txt", "text/plain", "text".getBytes());

    mockMvc
        .perform(multipart("/files/upload").file(upload))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", "Only MP3 files can be uploaded here."));
  }

  @Test
  void cutsSelectedItemsIntoTheSessionClipboard() throws Exception {
    when(files.resolveAll(List.of("a", "b")))
        .thenReturn(List.of(root.resolve("a"), root.resolve("b")));
    when(files.relative(any())).thenReturn("a", "b");
    MockHttpSession session = new MockHttpSession();

    mockMvc
        .perform(post("/files/cut").param("paths", "a", "b").session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("successMessage", "Cut 2 item(s). Paste them in a folder."));

    assertThat(session.getAttribute("songradar.fileClipboard")).isEqualTo(List.of("a", "b"));
  }

  @Test
  void reportsACutFailureWithItsMessage() throws Exception {
    when(files.resolveAll(anyList()))
        .thenThrow(new IllegalArgumentException("Select at least one item."));

    mockMvc
        .perform(post("/files/cut").param("paths", "a"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", "Select at least one item."));
  }

  @Test
  void rejectsPastingAnEmptyClipboard() throws Exception {
    mockMvc
        .perform(post("/files/paste").session(new MockHttpSession()))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", "The clipboard is empty."));
  }

  @Test
  void treatsANonListClipboardAsEmpty() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("songradar.fileClipboard", "not a list");

    mockMvc
        .perform(post("/files/paste").session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", "The clipboard is empty."));
  }

  @Test
  void reportsAnIllegalArgumentWithoutAMessageGenerically() throws Exception {
    doThrow(new IllegalArgumentException()).when(files).createFolder("", "New");

    mockMvc
        .perform(post("/files/folder").param("name", "New"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            flash().attribute("errorMessage", "That file operation could not be completed."));
  }

  @Test
  void pastesTheClipboardAndClearsIt() throws Exception {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute("songradar.fileClipboard", List.of("a", "b"));

    mockMvc
        .perform(post("/files/paste").param("scope", "library").session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/library"))
        .andExpect(flash().attribute("successMessage", "Items moved."));

    verify(files).paste(List.of("a", "b"), "library");
    assertThat(session.getAttribute("songradar.fileClipboard")).isNull();
  }

  /** The player deletes without leaving the page, so it needs a status rather than a redirect. */
  @Test
  void deletesASingleTrackWithoutRedirecting() throws Exception {
    mockMvc
        .perform(post("/files/delete-track").param("path", "library/Listener/song.mp3"))
        .andExpect(status().isNoContent());

    verify(files).delete(List.of("library/Listener/song.mp3"));
  }

  @Test
  void reportsARefusedTrackDeleteAsABadRequest() throws Exception {
    doThrow(new IllegalArgumentException("That item no longer exists."))
        .when(files)
        .delete(List.of("library/gone.mp3"));

    mockMvc
        .perform(post("/files/delete-track").param("path", "library/gone.mp3"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deletesSelectedItems() throws Exception {
    mockMvc
        .perform(post("/files/delete").param("paths", "a", "b"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("successMessage", "Items deleted."));

    verify(files).delete(List.of("a", "b"));
  }

  @Test
  void downloadsASingleFile() throws Exception {
    Path song = Files.writeString(root.resolve("song.mp3"), "music");
    when(files.file("song.mp3")).thenReturn(song);

    mockMvc
        .perform(get("/files/download").param("path", "song.mp3"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("filename=\"song.mp3\"")))
        .andExpect(header().longValue("Content-Length", 5));
  }

  @Test
  void streamsASelectionAsAZipArchive() throws Exception {
    Path archive = Files.writeString(root.resolve("selection.zip"), "zip");
    when(files.createArchive(List.of("a", "b"))).thenReturn(archive);

    mockMvc
        .perform(post("/files/download-selected").param("paths", "a", "b"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/zip"))
        .andExpect(
            header()
                .string("Content-Disposition", "attachment; filename=\"songradar-download.zip\""))
        .andExpect(header().longValue("Content-Length", 3));
  }

  @Test
  void servesAudioInlineWithItsMediaType() throws Exception {
    Path song = Files.writeString(root.resolve("song.mp3"), "music");
    when(files.file("song.mp3")).thenReturn(song);

    mockMvc
        .perform(get("/files/audio").param("path", "song.mp3"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "audio/mpeg"))
        .andExpect(header().string("Content-Disposition", containsString("filename=\"song.mp3\"")));
  }

  /** Tomcat drops a header it cannot encode, so the track would play with no name at all. */
  @Test
  void servesAudioWhoseNameIsNotAscii() throws Exception {
    Path song = Files.writeString(root.resolve("Reflex - Я разбила небо.mp3"), "music");
    when(files.file("Reflex - Я разбила небо.mp3")).thenReturn(song);

    mockMvc
        .perform(get("/files/audio").param("path", "Reflex - Я разбила небо.mp3"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition", containsString("filename*=UTF-8''Reflex%20-%20%D0%AF")));
  }

  @Test
  void servesUnknownAudioAsOctetStream() throws Exception {
    Path file = Files.writeString(root.resolve("notes.zzz"), "text");
    when(files.file("notes.zzz")).thenReturn(file);

    mockMvc
        .perform(get("/files/audio").param("path", "notes.zzz"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/octet-stream"))
        .andExpect(
            header().string("Content-Disposition", containsString("filename=\"notes.zzz\"")));
  }
}
