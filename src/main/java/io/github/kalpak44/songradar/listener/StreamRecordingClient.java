package io.github.kalpak44.songradar.listener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Captures HLS audio and decodes it to WAV.
 *
 * <p>The segments are fetched here rather than handed to ffmpeg as a URL: this keeps the stream
 * restricted to HTTP, rejects encrypted playlists and bounds what a playlist can ask for, none of
 * which survives passing a listener's own URL to a program that speaks a dozen other protocols.
 * What ffmpeg is given is a local file this process wrote. See docs/recording.md.
 */
@Component
public class StreamRecordingClient {

  private static final int BUFFER_SIZE = 16 * 1024;
  private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int READ_TIMEOUT_MILLIS = 15_000;

  /** Generous for a decode that reads a local file, short enough that a hang is not a stall. */
  private static final int DECODE_TIMEOUT_SECONDS = 120;

  private static final int RIFF = 0x46464952;
  private static final int WAVE = 0x45564157;
  private static final int FMT = 0x20746d66;
  private static final int DATA = 0x61746164;

  private final String ffmpeg;

  /**
   * Hyphen-free property name so the container can set it as SONGRADAR_RECORDING_FFMPEG. The image
   * already carries ffmpeg on PATH for yt-dlp, so the default needs no configuration.
   */
  public StreamRecordingClient(@Value("${songradar.recording.ffmpeg:ffmpeg}") String ffmpeg) {
    this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg.trim();
  }

  public Capture capture(URI stream, Path directory, String stem, Duration duration)
      throws IOException {
    if (!"http".equalsIgnoreCase(stream.getScheme())
        && !"https".equalsIgnoreCase(stream.getScheme())) {
      throw new IOException("the stream does not use HTTP");
    }
    if (duration.isZero() || duration.isNegative()) {
      throw new IOException("recording duration must be positive");
    }

    Files.createDirectories(directory);
    Path transport = directory.resolve(stem + ".ts.part");
    Path partialWav = directory.resolve(stem + ".wav.part");
    Path completedWav = directory.resolve(stem + ".wav");
    try {
      collectSegments(resolveMediaPlaylist(stream), transport, duration);
      long durationMillis = decodeToWav(transport, partialWav, duration);
      if (durationMillis < 250) {
        throw new IOException("the stream contained no decodable audio");
      }
      Path completed = Files.move(partialWav, completedWav, StandardCopyOption.REPLACE_EXISTING);
      return new Capture(completed, durationMillis);
    } finally {
      Files.deleteIfExists(transport);
      Files.deleteIfExists(partialWav);
    }
  }

  private static URI resolveMediaPlaylist(URI requested) throws IOException {
    URI current = requested;
    for (int depth = 0; depth < 4; depth++) {
      String body = fetchText(current);
      if (!body.stripLeading().startsWith("#EXTM3U")) {
        throw new IOException("the stream did not return an HLS playlist");
      }
      URI variant = firstVariant(body, current);
      if (variant == null) {
        if (body.contains("#EXTINF:")) {
          return current;
        }
        throw new IOException("the HLS playlist contains no audio segments");
      }
      current = variant;
    }
    throw new IOException("the HLS playlist has too many nested variants");
  }

  private static URI firstVariant(String playlist, URI base) {
    boolean expectUri = false;
    for (String line : playlist.split("\\R")) {
      String entry = line.trim();
      if (entry.startsWith("#EXT-X-STREAM-INF:")) {
        expectUri = true;
      } else if (expectUri && !entry.isEmpty() && !entry.startsWith("#")) {
        return resolveReference(base, entry);
      }
    }
    return null;
  }

  private static void collectSegments(URI mediaPlaylist, Path output, Duration duration)
      throws IOException {
    long targetMillis = duration.toMillis();
    long collectedMillis = 0;
    Set<URI> downloaded = new HashSet<>();
    boolean wroteSegment = false;
    long deadline = System.nanoTime() + duration.plusSeconds(30).toNanos();
    try (OutputStream transport = Files.newOutputStream(output)) {
      while (collectedMillis < targetMillis && System.nanoTime() < deadline) {
        String playlist = fetchText(mediaPlaylist);
        if (playlist.contains("#EXT-X-KEY:") && !playlist.contains("#EXT-X-KEY:METHOD=NONE")) {
          throw new IOException("encrypted HLS streams are not supported");
        }
        double segmentDurationSeconds = 0;
        boolean foundNewSegment = false;
        for (String line : playlist.split("\\R")) {
          String entry = line.trim();
          if (entry.startsWith("#EXTINF:")) {
            int comma = entry.indexOf(',');
            String value = entry.substring("#EXTINF:".length(), comma < 0 ? entry.length() : comma);
            try {
              segmentDurationSeconds = Double.parseDouble(value);
            } catch (NumberFormatException e) {
              segmentDurationSeconds = 0;
            }
          } else if (!entry.isEmpty() && !entry.startsWith("#")) {
            URI segment = resolveReference(mediaPlaylist, entry);
            if (downloaded.add(segment)) {
              copySegment(segment, transport);
              wroteSegment = true;
              foundNewSegment = true;
              collectedMillis += Math.max(1, Math.round(segmentDurationSeconds * 1000));
            }
            segmentDurationSeconds = 0;
            if (collectedMillis >= targetMillis) {
              break;
            }
          }
        }
        if (collectedMillis >= targetMillis || playlist.contains("#EXT-X-ENDLIST")) {
          break;
        }
        if (!foundNewSegment) {
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("recording was interrupted", e);
          }
        }
      }
    }
    if (!wroteSegment) {
      throw new IOException("the HLS playlist did not provide any segments");
    }
  }

  private static String fetchText(URI uri) throws IOException {
    HttpURLConnection connection = open(uri);
    try {
      connection.connect();
      if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
        throw new IOException("HLS playlist returned HTTP " + connection.getResponseCode());
      }
      try (InputStream input = connection.getInputStream();
          ByteArrayOutputStream body = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
          total += read;
          if (total > MAX_PLAYLIST_BYTES) {
            throw new IOException("HLS playlist is too large");
          }
          body.write(buffer, 0, read);
        }
        return body.toString(java.nio.charset.StandardCharsets.UTF_8);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static void copySegment(URI uri, OutputStream output) throws IOException {
    HttpURLConnection connection = open(uri);
    try {
      connection.connect();
      if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
        throw new IOException("HLS segment returned HTTP " + connection.getResponseCode());
      }
      try (InputStream input = connection.getInputStream()) {
        input.transferTo(output);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection open(URI uri) throws IOException {
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new IOException("the HLS playlist contains a non-HTTP URL");
    }
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    connection.setReadTimeout(READ_TIMEOUT_MILLIS);
    connection.setRequestProperty("User-Agent", "SongRadar/1.0");
    connection.setInstanceFollowRedirects(true);
    return connection;
  }

  private static URI resolveReference(URI base, String reference) {
    URI relative = URI.create(reference);
    URI resolved = base.resolve(relative);
    if (relative.getRawQuery() == null
        && resolved.getRawQuery() == null
        && base.getRawQuery() != null
        && sameOrigin(base, resolved)) {
      String value = resolved.toString();
      int fragment = value.indexOf('#');
      String query = "?" + base.getRawQuery();
      resolved =
          URI.create(
              fragment < 0
                  ? value + query
                  : value.substring(0, fragment) + query + value.substring(fragment));
    }
    return resolved;
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /**
   * ffmpeg decodes, rather than this process. The in-process AAC decoder this replaced answered
   * frames it could not handle with a full-length buffer of zeros and no error, so a recording came
   * back exactly the right length with holes punched through it and nothing here could tell. On the
   * same bitstream it blanked 3.7% of frames at 128 kbps and 42% at 96 kbps, all of which ffmpeg
   * decodes in full. See docs/recording.md.
   */
  private long decodeToWav(Path transport, Path wav, Duration duration) throws IOException {
    List<String> command =
        List.of(
            ffmpeg,
            // Never prompt and never read this process's stdin: a decode runs unattended,
            // and ffmpeg asks before overwriting.
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            transport.toString(),
            "-vn",
            // Stream artwork and timed metadata have no place in a recording the
            // fingerprinter reads as plain PCM.
            "-map_metadata",
            "-1",
            "-t",
            // Root locale: a comma decimal separator is not a duration to ffmpeg.
            String.format(Locale.ROOT, "%.3f", duration.toMillis() / 1000.0),
            "-c:a",
            "pcm_s16le",
            "-f",
            "wav",
            wav.toString());
    runFfmpeg(command);
    if (!Files.isRegularFile(wav)) {
      throw new IOException("ffmpeg produced no audio file");
    }
    return wavDurationMillis(wav);
  }

  /**
   * Arguments are passed as a list, never through a shell: the only one that is not a constant is a
   * path this process chose, and quoting it into a command line would be an injection.
   */
  private void runFfmpeg(List<String> command) throws IOException {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException exception) {
      // A missing binary is a deployment fault, not a bad stream, and reads as neither
      // unless it is said plainly.
      throw new IOException(
          "ffmpeg could not be run as \"" + ffmpeg + "\"; recordings cannot be decoded without it",
          exception);
    }
    try {
      // Waited on before its output is read: reading to end of stream first would block for as
      // long as a hung ffmpeg stays alive, and the timeout below would never come due. Safe in
      // this order only because --loglevel error cannot fill the pipe it is buffered in.
      if (!process.waitFor(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("ffmpeg did not finish within " + DECODE_TIMEOUT_SECONDS + "s");
      }
      String error =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (process.exitValue() != 0) {
        throw new IOException(
            "ffmpeg could not decode the stream"
                + (error.isEmpty() ? "" : ": " + error.lines().findFirst().orElse(error)));
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("interrupted while waiting for ffmpeg", exception);
    }
  }

  /**
   * Read from the file rather than from what was asked for: a stream that ended early gives ffmpeg
   * less than the requested duration, and the recording is as long as the audio in it.
   */
  private static long wavDurationMillis(Path wav) throws IOException {
    try (SeekableByteChannel channel = Files.newByteChannel(wav, StandardOpenOption.READ)) {
      long size = channel.size();
      ByteBuffer header = read(channel, 0, 12);
      if (header == null || header.getInt(0) != RIFF || header.getInt(8) != WAVE) {
        throw new IOException("ffmpeg did not return a RIFF/WAVE recording");
      }
      int channels = 0;
      int sampleRate = 0;
      int bitsPerSample = 0;
      long dataBytes = 0;
      long cursor = 12;
      while (cursor + 8 <= size) {
        ByteBuffer chunk = read(channel, cursor, 8);
        if (chunk == null) {
          break;
        }
        long length = Integer.toUnsignedLong(chunk.getInt(4));
        long body = cursor + 8;
        if (chunk.getInt(0) == FMT && length >= 16) {
          ByteBuffer format = read(channel, body, 16);
          if (format != null) {
            channels = format.getShort(2) & 0xffff;
            sampleRate = format.getInt(4);
            bitsPerSample = format.getShort(14) & 0xffff;
          }
        } else if (chunk.getInt(0) == DATA) {
          // Trust the file over the header: a decode that stopped mid-write still has a
          // usable prefix, and the declared length would then overstate it.
          dataBytes = Math.min(length, size - body);
        }
        cursor = body + length + (length & 1);
      }
      long bytesPerSecond = (long) sampleRate * channels * (bitsPerSample / 8);
      if (bytesPerSecond <= 0) {
        throw new IOException("the recording has no readable audio format");
      }
      return dataBytes * 1000 / bytesPerSecond;
    }
  }

  private static ByteBuffer read(SeekableByteChannel channel, long position, int length)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
    channel.position(position);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        return null;
      }
    }
    return buffer;
  }

  public record Capture(Path path, long durationMillis) {}
}
