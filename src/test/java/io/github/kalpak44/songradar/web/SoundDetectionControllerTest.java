package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import io.github.kalpak44.songradar.detection.SoundDetectionService;
import io.github.kalpak44.songradar.detection.SoundDetectionService.DownloadPlanResult;
import io.github.kalpak44.songradar.detection.SoundDetectionService.ManualAttemptResult;
import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SoundDetectionController.class)
@Import(SoundDetectionControllerTest.ClockTestConfiguration.class)
class SoundDetectionControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SoundDetectionService service;
  @MockitoBean private ListenerRecordingRepository recordings;

  @Test
  void emptyDetectionsPageClampsThePageAndExposesModelState() throws Exception {
    when(service.count()).thenReturn(0L);
    when(service.page(1, 30)).thenReturn(List.of());
    when(service.isConfigured()).thenReturn(false);
    when(service.pendingCount()).thenReturn(0L);

    mockMvc
        .perform(get("/detections").param("page", "9"))
        .andExpect(status().isOk())
        .andExpect(view().name("detections"))
        .andExpect(model().attribute("activeNav", "detections"))
        .andExpect(model().attribute("configured", false))
        .andExpect(model().attribute("pendingCount", 0L))
        .andExpect(model().attribute("total", 0L))
        .andExpect(model().attribute("currentPage", 1))
        .andExpect(model().attribute("totalPages", 1));

    verify(service).page(1, 30);
  }

  @Test
  void detectionsPageRendersDetectionWithoutLoadingItsSourceRecording() throws Exception {
    when(service.count()).thenReturn(31L);
    when(service.page(2, 30)).thenReturn(List.of(detection(1L, null)));
    when(service.isConfigured()).thenReturn(true);
    when(service.pendingCount()).thenReturn(2L);

    mockMvc
        .perform(get("/detections").param("page", "2"))
        .andExpect(status().isOk())
        .andExpect(view().name("detections"))
        .andExpect(model().attribute("currentPage", 2))
        .andExpect(model().attribute("totalPages", 2))
        .andExpect(content().string(containsString("Song")))
        .andExpect(content().string(containsString("Artist")))
        .andExpect(content().string(containsString("Open track")))
        .andExpect(content().string(containsString("Find &amp; download")));
    verify(recordings, never()).findById(any());
  }

  @Test
  void findAndDownloadReportsDummyPlan() throws Exception {
    when(service.planDownload(23L)).thenReturn(DownloadPlanResult.PLANNED);

    mockMvc
        .perform(post("/detections/23/download"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/detections"))
        .andExpect(
            flash()
                .attribute(
                    "successMessage",
                    "Download plan logged. The current dummy downloader does not create a file yet."));
  }

  @Test
  void findAndDownloadExplainsExpiredSourceRecording() throws Exception {
    when(service.planDownload(23L)).thenReturn(DownloadPlanResult.RECORDING_EXPIRED);

    mockMvc
        .perform(post("/detections/23/download"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/detections"))
        .andExpect(
            flash()
                .attribute(
                    "errorMessage",
                    "The source recording has expired, so its listener library folder is unavailable."));
  }

  @Test
  void missingRecordingCannotBeDetected() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.empty());

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(
            flash().attribute("errorMessage", "That recording is no longer available to detect."));

    verify(service, never()).manualAttempt(any());
  }

  @Test
  void recordingWithoutAFileCannotBeDetected() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.of(recording(14L, 7L, null)));

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(
            flash().attribute("errorMessage", "That recording is no longer available to detect."));

    verify(service, never()).manualAttempt(any());
  }

  @Test
  void queuedDetectionIsReported() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.of(recording()));
    when(service.manualAttempt(any())).thenReturn(ManualAttemptResult.QUEUED);

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(flash().attribute("successMessage", "Detection attempt queued."));
  }

  @Test
  void alreadyPendingDetectionIsReported() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.of(recording()));
    when(service.manualAttempt(any())).thenReturn(ManualAttemptResult.ALREADY_PENDING);

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(
            flash()
                .attribute(
                    "successMessage", "Detection is already in progress for that recording."));
  }

  @Test
  void disabledDetectionIsReported() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.of(recording()));
    when(service.manualAttempt(any())).thenReturn(ManualAttemptResult.DISABLED);

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(
            flash()
                .attribute(
                    "errorMessage",
                    "Detection is not configured. Add SONGRADAR_AUDD_API_TOKEN to enable it."));
  }

  @Test
  void invalidDetectionIsReported() throws Exception {
    when(recordings.findById(14L)).thenReturn(Optional.of(recording()));
    when(service.manualAttempt(any())).thenReturn(ManualAttemptResult.INVALID_RECORDING);

    mockMvc
        .perform(post("/recordings/14/detect"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings"))
        .andExpect(flash().attribute("errorMessage", "That recording cannot be detected."));
  }

  private static SoundDetection detection(long id, Long recordingId) {
    return new SoundDetection(
        id,
        recordingId,
        DetectionStatus.MATCHED,
        "AudD",
        "artist|song",
        "Song",
        "Artist",
        "Album",
        "2020",
        "https://e.com/song",
        NOW,
        NOW,
        1,
        null);
  }

  private static ListenerRecording recording() {
    return recording(14L, 7L, "4/7/capture.wav");
  }

  private static ListenerRecording recording(Long id, Long listenerId, String filePath) {
    return new ListenerRecording(
        id,
        listenerId,
        filePath,
        NOW.minusSeconds(60),
        NOW.minusSeconds(48),
        RecordingStatus.COMPLETED,
        1200,
        12_000,
        null);
  }

  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
