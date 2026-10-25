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

import io.github.kalpak44.songradar.listener.ListenerRecording;
import io.github.kalpak44.songradar.listener.ListenerRecordingRepository;
import io.github.kalpak44.songradar.listener.ListenerScheduler;
import io.github.kalpak44.songradar.listener.ListenerService;
import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import io.github.kalpak44.songradar.listener.RecordingService;
import io.github.kalpak44.songradar.listener.RecordingStatus;
import io.github.kalpak44.songradar.playlist.PlaylistChannel;
import io.github.kalpak44.songradar.playlist.PlaylistChannelService;
import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.PlaylistSourceService;
import io.github.kalpak44.songradar.playlist.PlaylistUnavailableException;
import io.github.kalpak44.songradar.playlist.SourceType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlaylistController.class)
@Import(PlaylistControllerTest.ClockTestConfiguration.class)
class PlaylistControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
  private static final PlaylistChannel CHANNEL =
      new PlaylistChannel("MTV Hits", "https://e.com/mtv");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private PlaylistSourceService playlists;
  @MockitoBean private PlaylistChannelService channels;
  @MockitoBean private ListenerService listeners;
  @MockitoBean private ListenerRecordingRepository recordings;
  @MockitoBean private RecordingService recordingService;
  @MockitoBean private ListenerScheduler scheduler;

  @BeforeEach
  void setUp() {
    when(playlists.find(4L)).thenReturn(Optional.of(playlist()));
  }

  @Test
  void detailRendersListenerStatusAndSchedule() throws Exception {
    when(listeners.findAll(4L)).thenReturn(List.of(listener(ListenerStatus.ACTIVE)));
    when(recordings.countCompletedByListenerId(7L)).thenReturn(6L);

    mockMvc
        .perform(get("/playlists/4"))
        .andExpect(status().isOk())
        .andExpect(view().name("playlist-detail"))
        .andExpect(model().attribute("activeNav", "settings"))
        .andExpect(content().string(containsString("MTV Listener")))
        .andExpect(content().string(containsString("Next recording")))
        .andExpect(content().string(containsString("6 saved")));
  }

  @Test
  void newListenerPageRendersSearchableChannels() throws Exception {
    when(channels.channels(4L)).thenReturn(List.of(CHANNEL));

    mockMvc
        .perform(get("/playlists/4/listeners/new"))
        .andExpect(status().isOk())
        .andExpect(view().name("listener-detail"))
        .andExpect(content().string(containsString("Search channels by name")))
        .andExpect(content().string(containsString("MTV Hits")));
  }

  @Test
  void editPageLoadsTheExistingListenerIntoTheForm() throws Exception {
    PlaylistListener listener = listener(ListenerStatus.ACTIVE);
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(listener));
    when(channels.channels(4L)).thenReturn(List.of(CHANNEL));

    mockMvc
        .perform(get("/playlists/4/listeners/7"))
        .andExpect(status().isOk())
        .andExpect(view().name("listener-detail"))
        .andExpect(model().attribute("editing", true))
        .andExpect(content().string(containsString("Save listener")));
  }

  @Test
  void unavailablePlaylistContentLeavesTheEditorUsableWithAWarning() throws Exception {
    when(channels.channels(4L))
        .thenThrow(new PlaylistUnavailableException("The playlist could not be loaded."));

    mockMvc
        .perform(get("/playlists/4/listeners/new"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("channelLoadError", "The playlist could not be loaded."))
        .andExpect(content().string(containsString("The playlist could not be loaded.")));
  }

  @Test
  void validListenerIsCreatedScheduledAndRedirected() throws Exception {
    PlaylistListener saved = listener(ListenerStatus.ACTIVE);
    when(channels.requireChannel(4L, CHANNEL.streamUrl())).thenReturn(CHANNEL);
    when(listeners.create(4L, "MTV Listener", CHANNEL, 3, 12, true)).thenReturn(saved);

    mockMvc
        .perform(
            post("/playlists/4/listeners")
                .param("name", "MTV Listener")
                .param("channelUrl", CHANNEL.streamUrl())
                .param("intervalMinutes", "3")
                .param("durationSeconds", "12")
                .param("active", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/playlists/4"))
        .andExpect(flash().attribute("successMessage", "Listener added."));

    verify(scheduler).refresh(saved);
  }

  @Test
  void validEditUpdatesAndReschedulesTheListener() throws Exception {
    PlaylistListener existing = listener(ListenerStatus.PAUSED);
    PlaylistListener saved = listener(ListenerStatus.ACTIVE);
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(existing));
    when(channels.requireChannel(4L, CHANNEL.streamUrl())).thenReturn(CHANNEL);
    when(listeners.update(existing, "MTV Listener", CHANNEL, 3, 12, true)).thenReturn(saved);

    mockMvc
        .perform(
            post("/playlists/4/listeners/7")
                .param("name", "MTV Listener")
                .param("channelUrl", CHANNEL.streamUrl())
                .param("intervalMinutes", "3")
                .param("durationSeconds", "12")
                .param("active", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/playlists/4"))
        .andExpect(flash().attribute("successMessage", "Listener updated."));

    verify(scheduler).refresh(saved);
  }

  @Test
  void durationMustBeShorterThanTheInterval() throws Exception {
    when(channels.requireChannel(4L, CHANNEL.streamUrl())).thenReturn(CHANNEL);
    when(channels.channels(4L)).thenReturn(List.of(CHANNEL));

    mockMvc
        .perform(
            post("/playlists/4/listeners")
                .param("name", "MTV Listener")
                .param("channelUrl", CHANNEL.streamUrl())
                .param("intervalMinutes", "1")
                .param("durationSeconds", "60"))
        .andExpect(status().isOk())
        .andExpect(view().name("listener-detail"))
        .andExpect(model().attributeHasFieldErrors("listenerForm", "durationSeconds"));

    verify(listeners, never())
        .create(
            any(Long.class),
            any(),
            any(),
            any(Integer.class),
            any(Integer.class),
            any(Boolean.class));
  }

  @Test
  void pauseUpdatesAndUnschedulesTheListener() throws Exception {
    PlaylistListener active = listener(ListenerStatus.ACTIVE);
    PlaylistListener paused = listener(ListenerStatus.PAUSED);
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(active));
    when(listeners.setActive(active, false)).thenReturn(paused);

    mockMvc
        .perform(post("/playlists/4/listeners/7/pause"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/playlists/4"));

    verify(scheduler).refresh(paused);
  }

  @Test
  void enableUpdatesAndSchedulesTheListener() throws Exception {
    PlaylistListener paused = listener(ListenerStatus.PAUSED);
    PlaylistListener active = listener(ListenerStatus.ACTIVE);
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(paused));
    when(listeners.setActive(paused, true)).thenReturn(active);

    mockMvc
        .perform(post("/playlists/4/listeners/7/enable"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("successMessage", "Listener enabled."));

    verify(scheduler).refresh(active);
  }

  @Test
  void deleteCancelsBeforeRemovingTheListener() throws Exception {
    PlaylistListener listener = listener(ListenerStatus.PAUSED);
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(listener));

    mockMvc
        .perform(post("/playlists/4/listeners/7/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("successMessage", "Listener deleted."));

    verify(scheduler).cancel(7L);
    verify(listeners).delete(listener);
  }

  @Test
  void deleteRecordingRemovesItsFileAndRedirectsToTheFilteredLibrary() throws Exception {
    when(listeners.find(4L, 7L)).thenReturn(Optional.of(listener(ListenerStatus.ACTIVE)));
    when(recordings.findById(14L))
        .thenReturn(
            Optional.of(
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

    mockMvc
        .perform(post("/playlists/4/listeners/7/recordings/14/delete"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/recordings?listenerId=7"))
        .andExpect(
            flash().attribute("successMessage", "Recording deleted and storage cleaned up."));

    verify(recordingService).delete(any(ListenerRecording.class));
  }

  @Test
  void missingPlaylistReturnsNotFound() throws Exception {
    when(playlists.find(99L)).thenReturn(Optional.empty());

    mockMvc.perform(get("/playlists/99")).andExpect(status().isNotFound());
  }

  private static PlaylistSource playlist() {
    return new PlaylistSource(4L, "Music TV", SourceType.URL, "https://e.com/list.m3u", null, NOW);
  }

  private static PlaylistListener listener(ListenerStatus status) {
    return new PlaylistListener(
        7L,
        4L,
        "MTV Listener",
        "MTV Hits",
        CHANNEL.streamUrl(),
        180,
        12,
        status,
        NOW.minusSeconds(3600),
        NOW,
        null,
        status == ListenerStatus.ACTIVE ? NOW.plusSeconds(180) : null);
  }

  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
