package io.github.kalpak44.songradar.web.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.kalpak44.songradar.listener.ListenerStatus;
import io.github.kalpak44.songradar.listener.PlaylistListener;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ListenerFormTest {

  @Test
  void buildsAnEditableFormFromAListener() {
    Instant now = Instant.parse("2026-10-24T12:00:00Z");
    PlaylistListener listener =
        new PlaylistListener(
            7L,
            4L,
            "Retro Listener",
            "VH1",
            "https://e.com/vh1",
            300,
            15,
            null,
            ListenerStatus.PAUSED,
            now,
            now,
            null,
            null);

    ListenerForm form = ListenerForm.from(listener);

    assertEquals("Retro Listener", form.getName());
    assertEquals("https://e.com/vh1", form.getChannelUrl());
    assertEquals(5, form.getIntervalMinutes());
    assertEquals(15, form.getDurationSeconds());
    assertFalse(form.isActive());
  }
}
