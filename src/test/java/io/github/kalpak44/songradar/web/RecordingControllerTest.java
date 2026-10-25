package io.github.kalpak44.songradar.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.ListenerService;
import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.RecordingService;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import io.github.kalpak44.songradar.playlist.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecordingController.class)
@Import(RecordingControllerTest.ClockConfiguration.class)
class RecordingControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ListenerRecordingRepository recordings;
  @MockitoBean private ListenerService listeners;
  @MockitoBean private PlaylistSourceService playlists;
  @MockitoBean private RecordingService recordingService;

  @Test
  void libraryGroupsRecordingsByListenerAndOffersPlaybackAndCleanup() throws Exception {
    when(recordings.countFiltered(null, null)).thenReturn(1L);
    when(recordings.findPageNewest(null, null, 25, 0))
        .thenReturn(
            List.of(
                new ListenerRecording(
                    14L,
                    7L,
                    "4/7/recording.wav",
                    NOW.minusSeconds(60),
                    NOW.minusSeconds(48),
                    RecordingStatus.COMPLETED,
                    1200,
                    12_000,
                    null)));
    when(listeners.findAll()).thenReturn(List.of(listener()));
    when(playlists.findAll()).thenReturn(List.of(playlist()));

    mockMvc
        .perform(get("/recordings"))
        .andExpect(status().isOk())
        .andExpect(view().name("recordings"))
        .andExpect(content().string(containsString("MTV Listener")))
        .andExpect(content().string(containsString("Music TV")))
        .andExpect(content().string(containsString("Delete")))
        .andExpect(content().string(containsString("Delete all")))
        .andExpect(content().string(containsString("Newest first")))
        .andExpect(content().string(containsString("audio")));
  }

  @Test
  void recordingsCanBeSortedAndPaginatedWhilePreservingFilters() throws Exception {
    when(recordings.countFiltered(7L, 4L)).thenReturn(50L);
    when(recordings.findPageOldest(7L, 4L, 25, 25)).thenReturn(List.of());

    mockMvc
        .perform(
            get("/recordings")
                .param("listenerId", "7")
                .param("playlistId", "4")
                .param("sort", "oldest")
                .param("page", "2"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("2 / 2")));

    verify(recordings).findPageOldest(7L, 4L, 25, 25);
  }

  @Test
  void deleteAllRemovesEveryRecordingAndRedirectsWithFeedback() throws Exception {
    when(recordingService.deleteAll()).thenReturn(new RecordingService.DeletionSummary(12, 0));

    mockMvc
        .perform(post("/recordings/delete-all"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                "/recordings"));

    verify(recordingService).deleteAll();
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
        null,
        ListenerStatus.ACTIVE,
        NOW,
        NOW,
        null,
        NOW.plusSeconds(180));
  }

  private static PlaylistSource playlist() {
    return new PlaylistSource(4L, "Music TV", SourceType.URL, "https://e.com/list.m3u", null, NOW);
  }

  static class ClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
