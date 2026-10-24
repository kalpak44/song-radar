package io.github.kalpak44.songradar.web;

import io.github.kalpak44.songradar.playlist.PlaylistSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * One row of the sources list, with the date already rendered. Formatting here rather than in the
 * template keeps the zone and locale in code a test can pin down.
 */
public record PlaylistSourceView(long id, String name, String typeLabel, String addedOn) {

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

  public static PlaylistSourceView of(PlaylistSource source, ZoneId zone) {
    return new PlaylistSourceView(
        source.id(),
        source.name(),
        source.type().getLabel(),
        DAY.format(source.addedAt().atZone(zone)));
  }

  public static List<PlaylistSourceView> of(List<PlaylistSource> sources, ZoneId zone) {
    return sources.stream().map(source -> of(source, zone)).toList();
  }
}
