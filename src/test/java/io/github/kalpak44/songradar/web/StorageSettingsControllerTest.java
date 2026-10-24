package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.storage.StorageService;
import io.github.kalpak44.songradar.storage.StorageSnapshot;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StorageSettingsController.class)
class StorageSettingsControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private StorageService storageService;

  @Test
  void pageRendersMetricsAndMarksStorageNavigationActive() throws Exception {
    when(storageService.snapshot()).thenReturn(snapshot());

    mockMvc
        .perform(get("/settings/storage"))
        .andExpect(status().isOk())
        .andExpect(view().name("storage"))
        .andExpect(model().attribute("activeNav", "storage"))
        .andExpect(content().string(containsString("Music storage")))
        .andExpect(content().string(containsString("/data/music")));
  }

  @Test
  void validLimitIsSavedWithAFormPost() throws Exception {
    mockMvc
        .perform(post("/settings/storage").param("limitGb", "20"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(flash().attribute("successMessage", "Storage limit saved."));

    verify(storageService).saveLimit(new BigDecimal("20"));
  }

  @Test
  void invalidLimitReturnsValidationFeedback() throws Exception {
    mockMvc
        .perform(post("/settings/storage").param("limitGb", "0"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(flash().attributeExists("storageLimitForm"))
        .andExpect(
            flash()
                .attributeExists("org.springframework.validation.BindingResult.storageLimitForm"));
  }

  private static StorageSnapshot snapshot() {
    return new StorageSnapshot(
        Path.of("/data/music"),
        100_000_000_000L,
        35_000_000_000L,
        65_000_000_000L,
        12_000_000_000L,
        20_000_000_000L);
  }
}
