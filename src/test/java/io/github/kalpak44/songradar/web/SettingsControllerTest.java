package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.detection.RecognitionProvider;
import io.github.kalpak44.songradar.detection.RecognitionProviders;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
@Import(SettingsControllerTest.ClockTestConfiguration.class)
class SettingsControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PlaylistSourceService service;
  @MockitoBean private RecognitionProviders recognition;

  @BeforeEach
  void selectAProvider() {
    when(recognition.active()).thenReturn(RecognitionProvider.AUDD);
    when(recognition.isConfigured(any(RecognitionProvider.class))).thenReturn(true);
  }

  @Test
  void settingsRendersSourcesAndMarksNavigationActive() throws Exception {
    when(service.findAll())
        .thenReturn(
            List.of(
                new PlaylistSource(
                    1L,
                    "Music",
                    io.github.kalpak44.songradar.playlist.SourceType.URL,
                    "https://e.com/list.m3u",
                    null,
                    NOW)));

    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(view().name("settings"))
        .andExpect(model().attribute("activeNav", "settings"))
        .andExpect(content().string(containsString("Music")));
  }

  @Test
  void invalidUrlReturnsAFieldErrorAfterRedirect() throws Exception {
    mockMvc
        .perform(post("/settings/sources/url").param("url", "not-a-url"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attributeExists("addUrlForm"))
        .andExpect(
            flash().attributeExists("org.springframework.validation.BindingResult.addUrlForm"));
  }

  @Test
  void validUrlIsSavedAndReportsTheChosenName() throws Exception {
    PlaylistSource saved =
        new PlaylistSource(
            1L,
            "Music",
            io.github.kalpak44.songradar.playlist.SourceType.URL,
            "https://e.com/list.m3u",
            null,
            NOW);
    when(service.addUrl("https://e.com/list.m3u", "Music")).thenReturn(saved);

    mockMvc
        .perform(
            post("/settings/sources/url")
                .param("url", "https://e.com/list.m3u")
                .param("name", "Music"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("successMessage", "Added \"Music\"."));

    verify(service).addUrl("https://e.com/list.m3u", "Music");
  }

  @Test
  void validUrlReportsADatabaseFailure() throws Exception {
    when(service.addUrl(any(), any())).thenThrow(new DataAccessResourceFailureException("offline"));

    mockMvc
        .perform(post("/settings/sources/url").param("url", "https://e.com/list.m3u"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "That playlist could not be saved."));
  }

  @Test
  void uploadRejectsAnEmptyFile() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "empty.m3u", null, new byte[0]);

    mockMvc
        .perform(multipart("/settings/sources/upload").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "Choose a file to upload."));
  }

  @Test
  void uploadRejectsAnUnsupportedExtension() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "channels.txt", "text/plain", "#EXTM3U".getBytes());

    mockMvc
        .perform(multipart("/settings/sources/upload").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "Only .m3u and .m3u8 files can be uploaded."));
  }

  @Test
  void validUploadIsStoredAndReportsTheChosenName() throws Exception {
    PlaylistSource saved =
        new PlaylistSource(
            2L,
            "Uploaded",
            io.github.kalpak44.songradar.playlist.SourceType.FILE,
            null,
            "stored.m3u",
            NOW);
    when(service.addFile(any(), any())).thenReturn(saved);
    MockMultipartFile file =
        new MockMultipartFile("file", "channels.m3u", null, "#EXTM3U".getBytes());

    mockMvc
        .perform(multipart("/settings/sources/upload").file(file).param("name", "Uploaded"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("successMessage", "Uploaded \"Uploaded\"."));

    verify(service).addFile(any(), any());
  }

  @Test
  void uploadReportsAStorageFailure() throws Exception {
    when(service.addFile(any(), any()))
        .thenThrow(new DataAccessResourceFailureException("offline"));
    MockMultipartFile file =
        new MockMultipartFile("file", "channels.m3u", null, "#EXTM3U".getBytes());

    mockMvc
        .perform(multipart("/settings/sources/upload").file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "That file could not be stored."));
  }

  @Test
  void uploadRejectsANameThatIsTooLongOnTheServer() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "channels.m3u", "audio/x-mpegurl", "#EXTM3U".getBytes());

    mockMvc
        .perform(multipart("/settings/sources/upload").file(file).param("name", "x".repeat(201)))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(
            flash().attribute("errorMessage", "Use a playlist name of at most 200 characters."));
  }

  @Test
  void removeUsesAPostAndReportsSuccess() throws Exception {
    when(service.remove(7L)).thenReturn(true);

    mockMvc
        .perform(post("/settings/sources/7/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("successMessage", "Playlist removed."));

    verify(service).remove(7L);
  }

  @Test
  void removeReportsWhenTheSourceIsAlreadyGone() throws Exception {
    when(service.remove(8L)).thenReturn(false);

    mockMvc
        .perform(post("/settings/sources/8/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "That playlist no longer exists."));
  }

  @Test
  void removeReportsAStorageFailure() throws Exception {
    when(service.remove(9L)).thenThrow(new DataAccessResourceFailureException("offline"));

    mockMvc
        .perform(post("/settings/sources/9/remove"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("errorMessage", "That playlist could not be removed."));
  }

  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Test
  void theSettingsPageListsEveryProviderAndMarksTheActiveOne() throws Exception {
    when(recognition.active()).thenReturn(RecognitionProvider.SHAZAM);

    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("recognitionProviders", hasSize(3)))
        // The bound form is what preselects a radio, so that is where the active provider shows.
        .andExpect(
            model()
                .attribute("recognitionProviderForm", hasProperty("provider", equalTo("shazam"))))
        .andExpect(content().string(containsString("Track recognition")))
        .andExpect(content().string(containsString("value=\"audd\"")))
        .andExpect(content().string(containsString("value=\"shazam\"")))
        .andExpect(content().string(containsString("value=\"none\"")));
  }

  @Test
  void aProviderThatIsNotReadyIsLabelledOnThePage() throws Exception {
    when(recognition.isConfigured(RecognitionProvider.AUDD)).thenReturn(false);

    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("SONGRADAR_AUDD_API_TOKEN")));
  }

  @Test
  void choosingAProviderSavesItAndSaysSo() throws Exception {
    mockMvc
        .perform(post("/settings/recognition").param("provider", "shazam"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings"))
        .andExpect(flash().attribute("successMessage", containsString("Shazam")));

    verify(recognition).select(RecognitionProvider.SHAZAM);
  }

  /** Saved even so: hiding the choice would leave the page disagreeing with the database. */
  @Test
  void choosingAProviderThatIsNotReadyStillSavesItAndWarns() throws Exception {
    when(recognition.isConfigured(RecognitionProvider.AUDD)).thenReturn(false);

    mockMvc
        .perform(post("/settings/recognition").param("provider", "audd"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", containsString("not ready to use")));

    verify(recognition).select(RecognitionProvider.AUDD);
  }

  @Test
  void anUnknownProviderIsRejectedWithoutSavingAnything() throws Exception {
    mockMvc
        .perform(post("/settings/recognition").param("provider", "acrcloud"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", containsString("does not exist")));

    verify(recognition, never()).select(any(RecognitionProvider.class));
  }

  @Test
  void aDatabaseFailureReportsTheProviderAsUnavailableRatherThanBreakingThePage() throws Exception {
    when(recognition.active()).thenThrow(new DataAccessResourceFailureException("down"));

    mockMvc
        .perform(get("/settings"))
        .andExpect(status().isOk())
        .andExpect(model().attributeExists("recognitionError"));
  }

  @Test
  void savingReportsADatabaseFailure() throws Exception {
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("down"))
        .when(recognition)
        .select(any(RecognitionProvider.class));

    mockMvc
        .perform(post("/settings/recognition").param("provider", "shazam"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("errorMessage", containsString("could not be saved")));
  }
}
