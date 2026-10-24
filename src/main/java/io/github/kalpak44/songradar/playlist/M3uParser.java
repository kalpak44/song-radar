package io.github.kalpak44.songradar.playlist;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Extracts named HTTP streams while preserving the order in the source playlist. */
public final class M3uParser {

  private M3uParser() {}

  public static List<PlaylistChannel> parse(String content, URI baseUri) {
    Map<String, PlaylistChannel> channels = new LinkedHashMap<>();
    String pendingName = null;
    for (String rawLine : content.replace("\uFEFF", "").lines().toList()) {
      String line = rawLine.trim();
      if (line.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
        pendingName = nameFromExtInf(line);
      } else if (!line.isEmpty() && !line.startsWith("#")) {
        URI stream = resolve(line, baseUri);
        if (stream != null && isHttp(stream)) {
          String name =
              pendingName == null || pendingName.isBlank() ? fallbackName(stream) : pendingName;
          channels.putIfAbsent(stream.toString(), new PlaylistChannel(name, stream.toString()));
        }
        pendingName = null;
      }
    }
    return List.copyOf(channels.values());
  }

  private static String nameFromExtInf(String line) {
    int comma = line.lastIndexOf(',');
    return comma < 0 ? null : line.substring(comma + 1).trim();
  }

  private static URI resolve(String value, URI baseUri) {
    try {
      URI uri = URI.create(value);
      return uri.isAbsolute() || baseUri == null ? uri : baseUri.resolve(uri);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  private static boolean isHttp(URI uri) {
    return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
  }

  private static String fallbackName(URI stream) {
    String path = stream.getPath();
    if (path != null && !path.isBlank() && !"/".equals(path)) {
      int slash = path.lastIndexOf('/');
      String segment = slash < 0 ? path : path.substring(slash + 1);
      if (!segment.isBlank()) {
        return segment;
      }
    }
    return stream.getHost() == null ? "Unnamed channel" : stream.getHost();
  }
}
