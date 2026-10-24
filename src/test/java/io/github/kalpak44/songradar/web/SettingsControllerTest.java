package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
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

import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettingsController.class)
@Import(SettingsControllerTest.ClockTestConfiguration.class)
class SettingsControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PlaylistSourceService service;

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

  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
