package io.github.kalpak44.songradar.listener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Captures stream bytes directly; no shell or external multimedia process is launched. */
@Component
public class StreamRecordingClient {

  private static final int BUFFER_SIZE = 16 * 1024;

  public Path capture(URI stream, Path directory, String stem, Duration duration)
      throws IOException {
    if (!"http".equalsIgnoreCase(stream.getScheme())
        && !"https".equalsIgnoreCase(stream.getScheme())) {
      throw new IOException("the stream does not use HTTP");
    }

    Files.createDirectories(directory);
    HttpURLConnection connection = (HttpURLConnection) stream.toURL().openConnection();
    connection.setConnectTimeout(10_000);
    connection.setReadTimeout(10_000);
    connection.setRequestProperty("User-Agent", "SongRadar/1.0");
    connection.setInstanceFollowRedirects(true);

    Path partial = directory.resolve(stem + ".part");
    try {
      connection.connect();
      Path completed = directory.resolve(stem + extension(connection.getContentType()));
      long deadline = System.nanoTime() + duration.toNanos();
      long written = 0;
      try (InputStream input = connection.getInputStream();
          OutputStream output = Files.newOutputStream(partial)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (System.nanoTime() < deadline) {
          int read = input.read(buffer);
          if (read < 0) {
            break;
          }
          output.write(buffer, 0, read);
          written += read;
        }
      }
      if (written == 0) {
        throw new IOException("the stream returned no audio data");
      }
      return Files.move(partial, completed, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      connection.disconnect();
      Files.deleteIfExists(partial);
    }
  }

  private static String extension(String contentType) {
    String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (normalized.contains("mpeg")) {
      return ".mp3";
    }
    if (normalized.contains("aac")) {
      return ".aac";
    }
    if (normalized.contains("mp2t")) {
      return ".ts";
    }
    return ".audio";
  }
}
