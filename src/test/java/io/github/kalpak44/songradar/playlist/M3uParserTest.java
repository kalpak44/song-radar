package io.github.kalpak44.songradar.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class M3uParserTest {

  @Test
  void parsesNamesAndResolvesRelativeStreamsInPlaylistOrder() {
    String content =
        """
        \uFEFF#EXTM3U
        #EXTINF:-1 tvg-id="mtv",MTV Hits
        streams/mtv.mp3
        #EXTINF:-1,Radio One
        https://radio.example/live.aac
        """;

    assertEquals(
        List.of(
            new PlaylistChannel("MTV Hits", "https://example.com/lists/streams/mtv.mp3"),
            new PlaylistChannel("Radio One", "https://radio.example/live.aac")),
        M3uParser.parse(content, URI.create("https://example.com/lists/channels.m3u")));
  }

  @Test
  void skipsUnsupportedAndMalformedStreamsAndDeduplicatesUrls() {
    String content =
        """
        #EXTM3U
        #EXTINF:-1,Local file
        file:///tmp/music.mp3
        #EXTINF:-1,Broken
        http:// bad
        #EXTINF:-1,First name wins
        https://example.com/live
        #EXTINF:-1,Duplicate
        https://example.com/live
        """;

    assertEquals(
        List.of(new PlaylistChannel("First name wins", "https://example.com/live")),
        M3uParser.parse(content, null));
  }

  @Test
  void derivesAReadableNameWhenExtInfIsMissing() {
    assertEquals(
        List.of(
            new PlaylistChannel("stream.mp3", "https://radio.example/path/stream.mp3"),
            new PlaylistChannel("radio.example", "https://radio.example/")),
        M3uParser.parse("https://radio.example/path/stream.mp3\nhttps://radio.example/", null));
  }
}
