package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StorageSettingsController.class)
class StorageSettingsControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private StorageService storageService;

  @Test
  void pageRendersMetricsAndMarksStorageNavigationActive() throws Exception {
    when(storageService.snapshot()).thenReturn(snapshot());
    when(storageService.recordsPerListener()).thenReturn(20);

    mockMvc
        .perform(get("/settings/storage"))
        .andExpect(status().isOk())
        .andExpect(view().name("storage"))
        .andExpect(model().attribute("activeNav", "storage"))
        .andExpect(content().string(containsString("Music storage")))
        .andExpect(content().string(containsString("/data/music")))
        .andExpect(content().string(containsString("Recording retention")))
        .andExpect(content().string(containsString("Maximum saved recordings per listener")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Limit in GB"))));
  }

  @Test
  void pageExplainsWhenStorageCannotBeInspected() throws Exception {
    when(storageService.snapshot())
        .thenThrow(new DataAccessResourceFailureException("database offline"));

    mockMvc
        .perform(get("/settings/storage"))
        .andExpect(status().isOk())
        .andExpect(view().name("storage"))
        .andExpect(model().attributeExists("storageError"))
        .andExpect(
            content().string(containsString("Storage information is temporarily unavailable")));
  }

  @Test
  void validRetentionIsSavedWithAFormPost() throws Exception {
    mockMvc
        .perform(post("/settings/storage/record-retention").param("recordsPerListener", "12"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(flash().attribute("successMessage", "Recording retention saved."));

    verify(storageService).saveRecordsPerListener(12);
  }

  @Test
  void invalidRetentionReturnsValidationFeedback() throws Exception {
    mockMvc
        .perform(post("/settings/storage/record-retention").param("recordsPerListener", "0"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(flash().attributeExists("recordRetentionForm"))
        .andExpect(
            flash()
                .attributeExists(
                    "org.springframework.validation.BindingResult.recordRetentionForm"));
  }

  @Test
  void serviceValidationFailureIsReturnedToTheForm() throws Exception {
    doThrow(new IllegalArgumentException("Keep between 1 and 1,000 recordings per listener."))
        .when(storageService)
        .saveRecordsPerListener(20);

    mockMvc
        .perform(post("/settings/storage/record-retention").param("recordsPerListener", "20"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(flash().attributeExists("recordRetentionForm"))
        .andExpect(
            flash()
                .attributeExists(
                    "org.springframework.validation.BindingResult.recordRetentionForm"));
  }

  @Test
  void saveReportsAStorageFailure() throws Exception {
    doThrow(new DataAccessResourceFailureException("offline"))
        .when(storageService)
        .saveRecordsPerListener(20);

    mockMvc
        .perform(post("/settings/storage/record-retention").param("recordsPerListener", "20"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/settings/storage"))
        .andExpect(
            flash()
                .attribute("errorMessage", "Recording retention could not be saved. Try again."));
  }

  private static StorageSnapshot snapshot() {
    return new StorageSnapshot(
        Path.of("/data/music"),
        100_000_000_000L,
        35_000_000_000L,
        65_000_000_000L,
        12_000_000_000L,
        true);
  }
}
