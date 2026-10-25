package io.github.kalpak44.songradar.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kalpak44.songradar.activity.ActivityEvent;
import io.github.kalpak44.songradar.activity.ActivityKind;
import io.github.kalpak44.songradar.activity.ActivityService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
class ActivityControllerTest {

  private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ActivityService activity;

  private static ActivityEvent event(ActivityKind kind, String track, String source, String text) {
    return new ActivityEvent(1L, NOW, kind, "Radio One", track, source, text);
  }

  @Test
  void emptyFeedRendersTheEmptyState() throws Exception {
    when(activity.lastDay()).thenReturn(List.of());
    when(activity.pageLimit()).thenReturn(500);

    mockMvc
        .perform(get("/activity"))
        .andExpect(status().isOk())
        .andExpect(view().name("activity"))
        .andExpect(model().attribute("activeNav", "activity"))
        .andExpect(model().attribute("truncated", false))
        .andExpect(content().string(Matchers.containsString("Nothing in the last day")));
  }

  @Test
  void rendersEveryStepOfThePipeline() throws Exception {
    when(activity.lastDay())
        .thenReturn(
            List.of(
                event(ActivityKind.SAVED, "Daft Punk - Get Lucky", "Audius", "Saved as a.mp3"),
                event(ActivityKind.SEARCHING, "Daft Punk - Get Lucky", "SoundCloud", "No match"),
                event(ActivityKind.DETECTED, "Daft Punk - Get Lucky", "AudD", "Identified"),
                event(ActivityKind.RECORDED, null, "Channel 1", "Captured 30s")));
    when(activity.pageLimit()).thenReturn(500);

    mockMvc
        .perform(get("/activity"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("Daft Punk - Get Lucky")))
        .andExpect(content().string(Matchers.containsString("SoundCloud")))
        .andExpect(content().string(Matchers.containsString("Saved as a.mp3")))
        .andExpect(content().string(Matchers.containsString("SEARCHING")));
  }

  /** A full page hides the oldest of the day, which would otherwise look like a quiet night. */
  @Test
  void saysSoWhenTheWindowIsFull() throws Exception {
    List<ActivityEvent> rows = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      rows.add(event(ActivityKind.RECORDED, null, "Channel 1", "Captured 30s"));
    }
    when(activity.lastDay()).thenReturn(rows);
    when(activity.pageLimit()).thenReturn(500);

    mockMvc
        .perform(get("/activity"))
        .andExpect(model().attribute("truncated", true))
        .andExpect(
            content().string(Matchers.containsString("Only the most recent entries are shown")));
  }

  @Test
  void clearingTheFeedSaysHowMuchWentAway() throws Exception {
    when(activity.clear()).thenReturn(7L);

    mockMvc
        .perform(post("/activity/clear"))
        .andExpect(redirectedUrl("/activity"))
        .andExpect(flash().attribute("successMessage", "Cleared 7 entries."));
  }

  @Test
  void clearingAnEmptyFeedSaysSo() throws Exception {
    when(activity.clear()).thenReturn(0L);

    mockMvc
        .perform(post("/activity/clear"))
        .andExpect(flash().attribute("successMessage", "There was nothing to clear."));
  }
}
