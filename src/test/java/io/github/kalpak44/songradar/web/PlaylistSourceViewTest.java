package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.kalpak44.songradar.playlist.PlaylistSource;
import io.github.kalpak44.songradar.playlist.SourceType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaylistSourceViewTest {

  private static final Instant ADDED = Instant.parse("2026-10-24T23:30:00Z");

  @Test
  void rendersTheTypeLabelAndTheDateInTheGivenZone() {
    PlaylistSource source =
        new PlaylistSource(1L, "Music TV", SourceType.URL, "https://e.com/a.m3u", null, ADDED);

    PlaylistSourceView view = PlaylistSourceView.of(source, ZoneId.of("UTC"));

    assertEquals(1L, view.id());
    assertEquals("Music TV", view.name());
    assertEquals("URL", view.typeLabel());
    assertEquals("Oct 24", view.addedOn());
  }

  @Test
  void theZoneDecidesTheDay() {
    PlaylistSource source =
        new PlaylistSource(2L, "Local", SourceType.FILE, null, "stored.m3u", ADDED);

    assertEquals("Oct 25", PlaylistSourceView.of(source, ZoneId.of("Europe/Sofia")).addedOn());
    assertEquals("File", PlaylistSourceView.of(source, ZoneId.of("UTC")).typeLabel());
  }

  @Test
  void convertsAWholeList() {
    List<PlaylistSourceView> views =
        PlaylistSourceView.of(
            List.of(
                new PlaylistSource(1L, "a", SourceType.URL, "https://e.com/a", null, ADDED),
                new PlaylistSource(2L, "b", SourceType.FILE, null, "b.m3u", ADDED)),
            ZoneId.of("UTC"));

    assertEquals(List.of("a", "b"), views.stream().map(PlaylistSourceView::name).toList());
  }
}
