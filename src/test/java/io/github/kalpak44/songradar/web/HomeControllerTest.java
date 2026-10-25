package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.storage.FileExplorerService;
import io.github.kalpak44.songradar.storage.LibraryFile;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FileExplorerController.class)
class HomeControllerTest {

  @TempDir Path temporaryRoot;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private FileExplorerService files;

  @Test
  void dashboardRendersFileExplorerAndMarksItsNavItemActive() throws Exception {
    Path library = temporaryRoot.resolve("library");
    when(files.root()).thenReturn(temporaryRoot);
    when(files.fileOrDirectory("library")).thenReturn(library);
    when(files.relative(library)).thenReturn("library");
    when(files.list("library")).thenReturn(List.of());

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard"))
        .andExpect(model().attribute("activeNav", "library"))
        .andExpect(content().string(containsString("Music library")))
        .andExpect(content().string(containsString("This folder is empty")));
  }

  @Test
  void libraryViewIsRootedAtLibraryAndKeepsItsOwnNavigationActive() throws Exception {
    Path library = temporaryRoot.resolve("library");
    when(files.root()).thenReturn(temporaryRoot);
    when(files.fileOrDirectory("library")).thenReturn(library);
    when(files.relative(library)).thenReturn("library");
    when(files.list("library")).thenReturn(List.of());

    mockMvc
        .perform(get("/library"))
        .andExpect(status().isOk())
        .andExpect(view().name("dashboard"))
        .andExpect(model().attribute("activeNav", "library"))
        .andExpect(model().attribute("currentPath", ""))
        .andExpect(
            content().string(containsString("Your saved tracks, organized into listener folders.")))
        .andExpect(content().string(containsString("library")));
  }

  @Test
  void rendersFolderRowsWithWorkingNavigationLinks() throws Exception {
    when(files.root()).thenReturn(temporaryRoot);
    when(files.relative(temporaryRoot)).thenReturn("");
    when(files.list(""))
        .thenReturn(List.of(new LibraryFile("playlists", "playlists", true, 0, Instant.now())));

    mockMvc
        .perform(get("/files"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("playlists")))
        .andExpect(content().string(containsString("href=\"/files?path=playlists\"")));
  }

  @Test
  void opensNestedFoldersWithoutLeavingAllFilesView() throws Exception {
    Path playlists = temporaryRoot.resolve("playlists");
    when(files.root()).thenReturn(temporaryRoot);
    when(files.fileOrDirectory("playlists")).thenReturn(playlists);
    when(files.relative(playlists)).thenReturn("playlists");
    when(files.list("playlists"))
        .thenReturn(List.of(new LibraryFile("local", "playlists/local", true, 0, Instant.now())));

    mockMvc
        .perform(get("/files").param("path", "playlists"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("currentPath", "playlists"))
        .andExpect(content().string(containsString("href=\"/files?path=playlists/local\"")));
  }
}
