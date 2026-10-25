package io.github.kalpak44.songradar.listener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import net.sourceforge.jaad.aac.AACException;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.common.AudioFormat;
import org.jcodec.common.model.AudioBuffer;
import org.springframework.stereotype.Component;

/** Captures HLS audio and decodes MPEG-TS/AAC to WAV without launching a process. */
@Component
public class StreamRecordingClient {

  private static final int BUFFER_SIZE = 16 * 1024;
  private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int READ_TIMEOUT_MILLIS = 15_000;

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

  private static long decodeToWav(Path transport, Path wav, Duration duration) throws IOException {
    byte[] aac = extractAac(transport);
    int cursor = nextAdtsFrame(aac, 0);
    if (cursor < 0) throw new IOException("the MPEG-TS stream has no ADTS AAC audio track");
    int firstLength = adtsFrameLength(aac, cursor);
    if (firstLength < 7 || cursor + firstLength > aac.length)
      throw new IOException("the MPEG-TS stream contains an incomplete AAC frame");
    AACDecoder decoder;
    try {
      decoder = new AACDecoder(ByteBuffer.wrap(aac, cursor, firstLength).slice());
    } catch (AACException e) {
      throw new IOException("the MPEG-TS AAC stream could not be initialized", e);
    }
    try (RandomAccessFile output = new RandomAccessFile(wav.toFile(), "rw")) {
      output.write(new byte[44]);
      AudioFormat format = null;
      int sampleRate = 0, channels = 0;
      long maxBytes = Long.MAX_VALUE, dataBytes = 0;
      while (cursor >= 0 && cursor + 7 <= aac.length) {
        int frameLength = adtsFrameLength(aac, cursor);
        if (frameLength < 7 || cursor + frameLength > aac.length) break;
        AudioBuffer decoded =
            decoder.decodeFrame(ByteBuffer.wrap(aac, cursor, frameLength).slice(), null);
        if (decoded != null) {
          if (format == null) {
            format = decoded.getFormat();
            if (!format.isSigned() || format.getSampleSizeInBits() != 16)
              throw new IOException("the AAC decoder returned an unsupported PCM format");
            channels = format.getChannels();
            sampleRate = format.getSampleRate();
            int bps = Math.multiplyExact(Math.multiplyExact(sampleRate, channels), 2);
            maxBytes = Math.multiplyExact((long) bps, duration.toMillis()) / 1000;
          }
          dataBytes += writePcm(output, decoded, maxBytes - dataBytes, format);
          if (dataBytes >= maxBytes) break;
        }
        cursor = nextAdtsFrame(aac, cursor + frameLength);
      }
      if (format == null || dataBytes == 0)
        throw new IOException("the MPEG-TS stream contains no decodable AAC audio");
      writeWavHeader(output, sampleRate, channels, dataBytes);
      return dataBytes * 1000 / (sampleRate * channels * 2L);
    } catch (ArithmeticException e) {
      throw new IOException("recording duration is too large", e);
    } catch (Exception e) {
      if (e instanceof IOException io) throw io;
      throw new IOException("the MPEG-TS AAC audio could not be decoded", e);
    }
  }

  private static byte[] extractAac(Path transport) throws IOException {
    byte[] ts = Files.readAllBytes(transport);
    ByteArrayOutputStream audio = new ByteArrayOutputStream();
    int pmtPid = -1, audioPid = -1;
    for (int offset = 0; offset + 188 <= ts.length; ) {
      if ((ts[offset] & 0xff) != 0x47) {
        offset++;
        continue;
      }
      int pid = ((ts[offset + 1] & 0x1f) << 8) | (ts[offset + 2] & 0xff);
      boolean start = (ts[offset + 1] & 0x40) != 0;
      int control = (ts[offset + 3] >> 4) & 3;
      int payload = offset + 4;
      if ((control & 2) != 0) payload += 1 + (ts[payload] & 0xff);
      int end = offset + 188;
      if ((control & 1) != 0 && payload < end) {
        if (pid == 0 && start) {
          int section = psiSectionStart(ts, payload, end);
          if (section >= 0 && (ts[section] & 0xff) == 0) pmtPid = parsePatForPmt(ts, section, end);
        } else if (pid == pmtPid && start) {
          int section = psiSectionStart(ts, payload, end);
          if (section >= 0 && (ts[section] & 0xff) == 2)
            audioPid = parsePmtForAac(ts, section, end);
        } else if (pid == audioPid && audioPid >= 0) {
          if (start
              && payload + 9 <= end
              && ts[payload] == 0
              && ts[payload + 1] == 0
              && ts[payload + 2] == 1) payload += 9 + (ts[payload + 8] & 0xff);
          if (payload < end) audio.write(ts, payload, end - payload);
        }
      }
      offset += 188;
    }
    if (audioPid < 0)
      throw new IOException("the MPEG-TS stream has no AAC audio elementary stream");
    return audio.toByteArray();
  }

  private static int psiSectionStart(byte[] bytes, int payload, int end) {
    int section = payload + 1 + (bytes[payload] & 0xff);
    return section < end ? section : -1;
  }

  private static int parsePatForPmt(byte[] b, int s, int end) {
    if (s + 8 > end) return -1;
    int stop = Math.min(end, s + 3 + (((b[s + 1] & 15) << 8) | (b[s + 2] & 255)) - 4);
    for (int i = s + 8; i + 4 <= stop; i += 4) {
      int program = ((b[i] & 255) << 8) | (b[i + 1] & 255);
      if (program != 0) return ((b[i + 2] & 31) << 8) | (b[i + 3] & 255);
    }
    return -1;
  }

  private static int parsePmtForAac(byte[] b, int s, int end) throws IOException {
    if (s + 12 > end) return -1;
    int stop = Math.min(end, s + 3 + (((b[s + 1] & 15) << 8) | (b[s + 2] & 255)) - 4);
    int i = s + 12 + (((b[s + 10] & 15) << 8) | (b[s + 11] & 255));
    while (i + 5 <= stop) {
      int type = b[i] & 255;
      int pid = ((b[i + 1] & 31) << 8) | (b[i + 2] & 255);
      int info = ((b[i + 3] & 15) << 8) | (b[i + 4] & 255);
      if (type == 15) return pid;
      if (type == 17)
        throw new IOException("the MPEG-TS stream uses AAC LATM, which is not supported yet");
      i += 5 + info;
    }
    return -1;
  }

  private static int nextAdtsFrame(byte[] b, int from) {
    for (int i = Math.max(0, from); i + 7 <= b.length; i++)
      if ((b[i] & 255) == 255 && (b[i + 1] & 0xf6) == 0xf0) return i;
    return -1;
  }

  private static int adtsFrameLength(byte[] b, int i) {
    if (i + 7 > b.length) return -1;
    return ((b[i + 3] & 3) << 11) | ((b[i + 4] & 255) << 3) | ((b[i + 5] & 0xe0) >> 5);
  }

  private static long writePcm(
      RandomAccessFile output, AudioBuffer buffer, long maxBytes, AudioFormat format)
      throws IOException {
    if (buffer == null || maxBytes <= 0) {
      return 0;
    }
    ByteBuffer pcm = buffer.getData().duplicate();
    int count = (int) Math.min(pcm.remaining(), maxBytes);
    count -= count % format.getFrameSize();
    byte[] bytes = new byte[count];
    pcm.get(bytes);
    if (format.isBigEndian()) {
      for (int index = 0; index + 1 < bytes.length; index += 2) {
        byte high = bytes[index];
        bytes[index] = bytes[index + 1];
        bytes[index + 1] = high;
      }
    }
    output.write(bytes);
    return count;
  }

  private static void writeWavHeader(
      RandomAccessFile file, int sampleRate, int channels, long dataBytes) throws IOException {
    if (dataBytes > 0xffff_ffffL - 36) {
      throw new IOException("recording is too large for a WAV file");
    }
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    header.putInt((int) (36 + dataBytes));
    header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    header.putInt(16);
    header.putShort((short) 1);
    header.putShort((short) channels);
    header.putInt(sampleRate);
    header.putInt(sampleRate * channels * 2);
    header.putShort((short) (channels * 2));
    header.putShort((short) 16);
    header.put("data".getBytes(StandardCharsets.US_ASCII));
    header.putInt((int) dataBytes);
    file.seek(0);
    file.write(header.array());
  }

  public record Capture(Path path, long durationMillis) {}
}
