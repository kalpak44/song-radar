package io.github.kalpak44.songradar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.detection.DetectionStatus;
import io.github.kalpak44.songradar.detection.SoundDetection;
import io.github.kalpak44.songradar.detection.SoundDetectionService;
import io.github.kalpak44.songradar.detection.SoundDetectionService.ManualAttemptResult;
import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.PlaylistListenerRepository;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceRepository;
import io.github.kalpak44.songradar.playlist.SourceType;
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
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(SoundDetectionController.class)
@Import(SoundDetectionControllerTest.ClockTestConfiguration.class)
class SoundDetectionControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SoundDetectionService service;
  @MockitoBean private ListenerRecordingRepository recordings;
  @MockitoBean private PlaylistListenerRepository listeners;
  @MockitoBean private PlaylistSourceRepository playlists;

  @Test
  void emptyDetectionsPageClampsThePageAndExposesModelState() throws Exception {
    when(service.count()).thenReturn(0L);
    when(service.page(1, 30)).thenReturn(List.of());
    when(service.isConfigured()).thenReturn(false);
    when(service.pendingCount()).thenReturn(0L);
    when(listeners.findAll()).thenReturn(List.of());
    when(playlists.findAll()).thenReturn(List.of());

    mockMvc
        .perform(get("/detections").param("page", "9"))
        .andExpect(status().isOk())
        .andExpect(view().name("detections"))
        .andExpect(model().attribute("activeNav", "detections"))
        .andExpect(model().attribute("configured", false))
        .andExpect(model().attribute("pendingCount", 0L))
        .andExpect(model().attribute("total", 0L))
        .andExpect(model().attribute("currentPage", 1))
        .andExpect(model().attribute("totalPages", 1))
        .andExpect(model().attribute("localZone", "Z"));

    verify(service).page(1, 30);
  }

  @Test
  void detectionsPageMapsListenerPlaylistAndRecordingDetails() throws Exception {
    when(service.count()).thenReturn(31L);
    when(service.page(2, 30))
        .thenReturn(List.of(detection(1L, 14L), detection(2L, null), detection(3L, 99L)));
    when(service.isConfigured()).thenReturn(true);
    when(service.pendingCount()).thenReturn(2L);
    when(listeners.findAll()).thenReturn(List.of(listener()));
    when(playlists.findAll()).thenReturn(List.of(playlist()));
    when(recordings.findById(14L)).thenReturn(Optional.of(recording()));
    when(recordings.findById(99L)).thenReturn(Optional.empty());

    MvcResult result =
        mockMvc
            .perform(get("/detections").param("page", "2"))
            .andExpect(status().isOk())
            .andExpect(view().name("detections"))
            .andExpect(model().attribute("currentPage", 2))
            .andExpect(model().attribute("totalPages", 2))
            .andReturn();

    @SuppressWarnings("unchecked")
    List<SoundDetectionController.DetectionRow> rows =
        (List<SoundDetectionController.DetectionRow>)
            result.getModelAndView().getModel().get("rows");

    assertThat(rows).hasSize(3);
    assertThat(rows.get(0).listenerName()).isEqualTo("MTV Listener");
    assertThat(rows.get(0).playlistName()).isEqualTo("Music TV");
    assertThat(rows.get(0).recordingId()).isEqualTo(14L);
    assertThat(rows.get(0).recordedAt()).isEqualTo(NOW.minusSeconds(48));
    assertThat(rows.get(1).listenerName()).isEqualTo("Removed listener");
    assertThat(rows.get(1).channelName()).isEmpty();
    assertThat(rows.get(1).recordingId()).isNull();
    assertThat(rows.get(2).listenerName()).isEqualTo("Removed listener");
    assertThat(rows.get(2).playlistName()).isEqualTo("Removed playlist");
    assertThat(rows.get(2).recordedAt()).isNull();
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

  private static PlaylistListener listener() {
    return new PlaylistListener(
        7L,
        4L,
        "MTV Listener",
        "MTV Hits",
        "https://e.com/mtv",
        180,
        12,
        ListenerStatus.ACTIVE,
        NOW,
        NOW,
        null,
        NOW.plusSeconds(180));
  }

  private static PlaylistSource playlist() {
    return new PlaylistSource(4L, "Music TV", SourceType.URL, "https://e.com/list", null, NOW);
  }

  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
