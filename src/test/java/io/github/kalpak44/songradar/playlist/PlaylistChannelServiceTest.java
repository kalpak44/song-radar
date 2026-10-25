package io.github.kalpak44.songradar.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaylistChannelServiceTest {

  @TempDir java.nio.file.Path root;

  private PlaylistSourceRepository sources;
  private PlaylistStorage storage;
  private PlaylistChannelService service;

  @BeforeEach
  void setUp() {
    sources = mock(PlaylistSourceRepository.class);
    storage = mock(PlaylistStorage.class);
    service = new PlaylistChannelService(sources, storage);
  }

  @Test
  void readsAndParsesAnUploadedPlaylist() {
    PlaylistSource source =
        PlaylistSource.ofFile("Local", "stored.m3u", Instant.parse("2026-10-24T12:00:00Z"));
    when(sources.findById(1L)).thenReturn(Optional.of(source));
    when(storage.open("stored.m3u"))
        .thenReturn(stream("#EXTM3U\n#EXTINF:-1,MTV Hits\nhttps://e.com/mtv"));

    assertEquals(
        List.of(new PlaylistChannel("MTV Hits", "https://e.com/mtv")), service.channels(1L));
  }

  @Test
  void requireChannelReturnsOnlyAChannelFromTheCurrentPlaylist() {
    PlaylistSource source =
        PlaylistSource.ofFile("Local", "stored.m3u", Instant.parse("2026-10-24T12:00:00Z"));
    when(sources.findById(1L)).thenReturn(Optional.of(source));
    when(storage.open("stored.m3u"))
        .thenReturn(stream("#EXTM3U\n#EXTINF:-1,MTV Hits\nhttps://e.com/mtv"));

    assertEquals("MTV Hits", service.requireChannel(1L, "https://e.com/mtv").name());

    when(storage.open("stored.m3u"))
        .thenReturn(stream("#EXTM3U\n#EXTINF:-1,MTV Hits\nhttps://e.com/mtv"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.requireChannel(1L, "https://attacker.example/stream"));
  }

  @Test
  void missingPlaylistHasAClearFailure() {
    when(sources.findById(9L)).thenReturn(Optional.empty());

    PlaylistUnavailableException failure =
        assertThrows(PlaylistUnavailableException.class, () -> service.channels(9L));

    assertEquals("That playlist no longer exists.", failure.getMessage());
  }

  @Test
  void loadsADirectUrlPlaylist() throws Exception {
    java.nio.file.Path playlist = root.resolve("remote.m3u");
    java.nio.file.Files.writeString(playlist, "#EXTM3U\n#EXTINF:-1,MTV Hits\nhttps://e.com/mtv");
    PlaylistSource source =
        PlaylistSource.ofUrl("Remote", playlist.toUri().toString(), Instant.now());
    when(sources.findById(1L)).thenReturn(Optional.of(source));

    assertEquals(
        List.of(new PlaylistChannel("MTV Hits", "https://e.com/mtv")), service.channels(1L));
  }

  @Test
  void wrapsAnUnreadablePlaylist() {
    PlaylistSource source =
        PlaylistSource.ofFile("Local", "missing.m3u", Instant.parse("2026-10-24T12:00:00Z"));
    when(sources.findById(1L)).thenReturn(Optional.of(source));
    when(storage.open("missing.m3u"))
        .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("missing")));

    PlaylistUnavailableException failure =
        assertThrows(PlaylistUnavailableException.class, () -> service.channels(1L));

    assertEquals("The playlist could not be loaded.", failure.getMessage());
  }

  private static ByteArrayInputStream stream(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
