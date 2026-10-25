package io.github.kalpak44.songradar.library;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Just enough MP4 to hand raw AAC frames to the decoder: the catalogues serve AAC in MP4, and the
 * image carries no demuxer. Boxes are walked rather than searched by name, so a four-byte sequence
 * inside sample data cannot be mistaken for a box.
 */
final class Mp4Aac {

  /** A sample entry carries a fixed 28-byte header before its child boxes. */
  private static final int SAMPLE_ENTRY_HEADER = 28;

  private Mp4Aac() {}

  record Box(String type, int start, int size) {
    int payload() {
      return start + 8;
    }

    int end() {
      return start + size;
    }
  }

  static List<Box> boxes(byte[] data, int from, int to) {
    List<Box> found = new ArrayList<>();
    int offset = from;
    while (offset + 8 <= to) {
      int size = ByteBuffer.wrap(data, offset, 4).getInt();
      if (size < 8 || offset + size > to) {
        break;
      }
      found.add(new Box(new String(data, offset + 4, 4, StandardCharsets.US_ASCII), offset, size));
      offset += size;
    }
    return found;
  }

  static Optional<Box> child(byte[] data, Box parent, String type) {
    return boxes(data, parent.payload(), parent.end()).stream()
        .filter(box -> box.type().equals(type))
        .findFirst();
  }

  static Optional<Box> path(byte[] data, int from, int to, String... types) {
    Optional<Box> current =
        boxes(data, from, to).stream().filter(box -> box.type().equals(types[0])).findFirst();
    for (int i = 1; i < types.length && current.isPresent(); i++) {
      current = child(data, current.get(), types[i]);
    }
    return current;
  }

  /**
   * The AudioSpecificConfig the decoder is constructed from, read from the sample entry's esds.
   * Present in a progressive file's moov and in an fMP4 init segment alike.
   */
  static byte[] audioSpecificConfig(byte[] data) {
    Box stsd =
        path(data, 0, data.length, "moov", "trak", "mdia", "minf", "stbl", "stsd")
            .orElseThrow(() -> new IllegalStateException("the audio has no sample description"));
    // stsd payload: version/flags, then an entry count, then the sample entries themselves.
    List<Box> entries = boxes(data, stsd.payload() + 8, stsd.end());
    for (Box entry : entries) {
      Optional<Box> esds =
          boxes(data, entry.payload() + SAMPLE_ENTRY_HEADER, entry.end()).stream()
              .filter(box -> box.type().equals("esds"))
              .findFirst();
      if (esds.isPresent()) {
        return decoderSpecificInfo(data, esds.get());
      }
    }
    throw new IllegalStateException("the audio carries no decoder configuration");
  }

  /** Walks the esds descriptor chain to tag 0x05, whose payload is the config itself. */
  private static byte[] decoderSpecificInfo(byte[] data, Box esds) {
    int offset = esds.payload() + 4;
    while (offset < esds.end()) {
      int tag = data[offset++] & 0xff;
      int size = 0;
      int piece;
      do {
        piece = data[offset++] & 0xff;
        size = (size << 7) | (piece & 0x7f);
      } while ((piece & 0x80) != 0 && offset < esds.end());
      switch (tag) {
        case 0x05 -> {
          byte[] config = new byte[size];
          System.arraycopy(data, offset, config, 0, size);
          return config;
        }
        // ES and DecoderConfig descriptors are containers; step over their own fields only.
        case 0x03 -> offset += 3;
        case 0x04 -> offset += 13;
        default -> offset += size;
      }
    }
    throw new IllegalStateException("the audio carries no decoder configuration");
  }

  /** Frames of one fMP4 fragment, sized by its trun and sliced out of its mdat. */
  static List<byte[]> fragmentFrames(byte[] fragment) {
    List<Box> top = boxes(fragment, 0, fragment.length);
    Box moof =
        top.stream()
            .filter(box -> box.type().equals("moof"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("the fragment has no movie fragment box"));
    Box mdat =
        top.stream()
            .filter(box -> box.type().equals("mdat"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("the fragment has no media data"));
    Box trun =
        child(fragment, moof, "traf")
            .flatMap(traf -> child(fragment, traf, "trun"))
            .orElseThrow(() -> new IllegalStateException("the fragment has no sample table"));

    ByteBuffer buffer = ByteBuffer.wrap(fragment);
    buffer.position(trun.payload());
    int flags = buffer.getInt() & 0xffffff;
    int count = buffer.getInt();
    if ((flags & 0x000001) != 0) {
      buffer.getInt();
    }
    if ((flags & 0x000004) != 0) {
      buffer.getInt();
    }

    List<byte[]> frames = new ArrayList<>(count);
    int offset = mdat.payload();
    for (int i = 0; i < count; i++) {
      if ((flags & 0x000100) != 0) {
        buffer.getInt();
      }
      int size = (flags & 0x000200) != 0 ? buffer.getInt() : 0;
      if ((flags & 0x000400) != 0) {
        buffer.getInt();
      }
      if ((flags & 0x000800) != 0) {
        buffer.getInt();
      }
      if (size <= 0 || offset + size > mdat.end()) {
        break;
      }
      frames.add(java.util.Arrays.copyOfRange(fragment, offset, offset + size));
      offset += size;
    }
    return frames;
  }

  /**
   * Frames of a progressive file, whose samples are addressed by the chunk tables rather than laid
   * out one fragment at a time.
   */
  static List<byte[]> progressiveFrames(byte[] data) {
    Box stbl =
        path(data, 0, data.length, "moov", "trak", "mdia", "minf", "stbl")
            .orElseThrow(() -> new IllegalStateException("the audio has no sample table"));
    int[] sizes = sampleSizes(data, stbl);
    long[] chunks = chunkOffsets(data, stbl);
    int[][] runs = sampleToChunk(data, stbl);

    List<byte[]> frames = new ArrayList<>(sizes.length);
    int sample = 0;
    for (int chunk = 0; chunk < chunks.length && sample < sizes.length; chunk++) {
      int perChunk = samplesInChunk(runs, chunk);
      long offset = chunks[chunk];
      for (int i = 0; i < perChunk && sample < sizes.length; i++, sample++) {
        int size = sizes[sample];
        if (offset + size > data.length) {
          return frames;
        }
        frames.add(java.util.Arrays.copyOfRange(data, (int) offset, (int) offset + size));
        offset += size;
      }
    }
    return frames;
  }

  private static int[] sampleSizes(byte[] data, Box stbl) {
    Box stsz =
        child(data, stbl, "stsz")
            .orElseThrow(() -> new IllegalStateException("the audio has no sample sizes"));
    ByteBuffer buffer = ByteBuffer.wrap(data);
    buffer.position(stsz.payload() + 4);
    int uniform = buffer.getInt();
    int count = buffer.getInt();
    int[] sizes = new int[count];
    for (int i = 0; i < count; i++) {
      sizes[i] = uniform != 0 ? uniform : buffer.getInt();
    }
    return sizes;
  }

  private static long[] chunkOffsets(byte[] data, Box stbl) {
    Optional<Box> stco = child(data, stbl, "stco");
    Box box =
        stco.or(() -> child(data, stbl, "co64"))
            .orElseThrow(() -> new IllegalStateException("the audio has no chunk offsets"));
    boolean wide = box.type().equals("co64");
    ByteBuffer buffer = ByteBuffer.wrap(data);
    buffer.position(box.payload() + 4);
    int count = buffer.getInt();
    long[] offsets = new long[count];
    for (int i = 0; i < count; i++) {
      offsets[i] = wide ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
    }
    return offsets;
  }

  private static int[][] sampleToChunk(byte[] data, Box stbl) {
    Box stsc =
        child(data, stbl, "stsc")
            .orElseThrow(() -> new IllegalStateException("the audio has no chunk map"));
    ByteBuffer buffer = ByteBuffer.wrap(data);
    buffer.position(stsc.payload() + 4);
    int count = buffer.getInt();
    int[][] runs = new int[count][2];
    for (int i = 0; i < count; i++) {
      runs[i][0] = buffer.getInt() - 1;
      runs[i][1] = buffer.getInt();
      buffer.getInt();
    }
    return runs;
  }

  private static int samplesInChunk(int[][] runs, int chunk) {
    int perChunk = 0;
    for (int[] run : runs) {
      if (run[0] <= chunk) {
        perChunk = run[1];
      }
    }
    return perChunk;
  }
}
