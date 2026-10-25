package io.github.kalpak44.songradar.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class Mp4AacTest {

  @Test
  void walksBoxesInOrderAndStopsAtAnInvalidSize() {
    byte[] data =
        concat(
            box("ftyp", ints(0)),
            box("moov", new byte[0]),
            new byte[] {0, 0, 0, 0, 'j', 'u', 'n', 'k'});

    List<Mp4Aac.Box> boxes = Mp4Aac.boxes(data, 0, data.length);

    assertThat(boxes).extracting(Mp4Aac.Box::type).containsExactly("ftyp", "moov");
    assertThat(boxes.getFirst().payload()).isEqualTo(8);
    assertThat(boxes.getFirst().end()).isEqualTo(12);
  }

  @Test
  void walksBoxesFromAnOffset() {
    byte[] data = concat(box("skip", new byte[0]), box("moov", new byte[0]));

    assertThat(Mp4Aac.boxes(data, 8, data.length))
        .extracting(Mp4Aac.Box::type)
        .containsExactly("moov");
  }

  @Test
  void findsChildrenAndWalksPaths() {
    byte[] data = box("moov", box("trak", box("mdia", new byte[0])));

    Mp4Aac.Box moov = Mp4Aac.boxes(data, 0, data.length).getFirst();
    assertThat(Mp4Aac.child(data, moov, "trak")).isPresent();
    assertThat(Mp4Aac.child(data, moov, "trak"))
        .get()
        .extracting(Mp4Aac.Box::type)
        .isEqualTo("trak");
    assertThat(Mp4Aac.path(data, 0, data.length, "moov", "trak", "mdia")).isPresent();
    assertThat(Mp4Aac.path(data, 0, data.length, "moov", "trak", "missing")).isEmpty();
  }

  @Test
  void readsTheDecoderConfigurationFromAProgressiveFile() {
    byte[] config = {0x12, 0x10};
    byte[] data = audioConfigFile(config);

    assertThat(Mp4Aac.audioSpecificConfig(data)).isEqualTo(config);
  }

  @Test
  void readsTheDecoderConfigurationWhenAnEntryWithoutEsdsComesFirst() {
    byte[] config = {0x11, 0x22};
    byte[] first = box("mp4a", new byte[28]);
    byte[] stsd = box("stsd", concat(ints(0), ints(2), first, sampleEntry(config)));
    byte[] data =
        concat(
            box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd))))),
            box("mdat", new byte[0]));

    assertThat(Mp4Aac.audioSpecificConfig(data)).isEqualTo(config);
  }

  @Test
  void rejectsAudioWithoutASampleDescription() {
    byte[] data = box("moov", box("trak", box("mdia", box("minf", box("stbl", new byte[0])))));

    assertThatThrownBy(() -> Mp4Aac.audioSpecificConfig(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no sample description");
  }

  @Test
  void rejectsASampleEntryWithoutEsds() {
    byte[] stsd = box("stsd", concat(ints(0), ints(1), box("mp4a", new byte[28])));
    byte[] data = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))));

    assertThatThrownBy(() -> Mp4Aac.audioSpecificConfig(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no decoder configuration");
  }

  @Test
  void rejectsAnEsdsWithoutADecoderConfigDescriptor() {
    byte[] esds = box("esds", concat(ints(0), descriptor(0x03, new byte[3])));
    byte[] stsd = box("stsd", concat(ints(0), ints(1), box("mp4a", concat(new byte[28], esds))));
    byte[] data = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))));

    assertThatThrownBy(() -> Mp4Aac.audioSpecificConfig(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no decoder configuration");
  }

  @Test
  void slicesFramesOutOfAFragmentUsingItsDataOffsetAndSampleSizes() {
    byte[] data = fragment(0x000001 | 0x000200, new int[] {3, 4});
    byte[] payload = mdatPayload(data);

    List<byte[]> frames = Mp4Aac.fragmentFrames(data);

    assertThat(frames).hasSize(2);
    assertThat(frames.get(0)).hasSize(3).isEqualTo(slice(payload, 0, 3));
    assertThat(frames.get(1)).hasSize(4).isEqualTo(slice(payload, 3, 4));
  }

  @Test
  void readsEveryOptionalTrunField() {
    byte[] data =
        fragment(0x000001 | 0x000004 | 0x000100 | 0x000200 | 0x000400 | 0x000800, new int[] {2, 3});

    assertThat(Mp4Aac.fragmentFrames(data)).hasSize(2);
  }

  @Test
  void stopsAtAZeroSizedSample() {
    byte[] data = fragment(0x000001 | 0x000200, new int[] {2, 0, 3});

    assertThat(Mp4Aac.fragmentFrames(data)).hasSize(1);
  }

  @Test
  void stopsWhenASampleRunsPastTheMediaData() {
    byte[] data = fragment(0x000001 | 0x000200, new int[] {2, 200}, 10);

    assertThat(Mp4Aac.fragmentFrames(data)).hasSize(1);
  }

  @Test
  void rejectsAFragmentWithoutAMoof() {
    byte[] data = box("mdat", new byte[] {1, 2, 3});

    assertThatThrownBy(() -> Mp4Aac.fragmentFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("movie fragment");
  }

  @Test
  void rejectsAFragmentWithoutMediaData() {
    byte[] data = box("moof", box("traf", box("trun", trun(0x000001, new int[] {1}, 0))));

    assertThatThrownBy(() -> Mp4Aac.fragmentFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("media data");
  }

  @Test
  void rejectsAFragmentWithoutASampleTable() {
    byte[] data = concat(box("moof", box("traf", new byte[0])), box("mdat", new byte[0]));

    assertThatThrownBy(() -> Mp4Aac.fragmentFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sample table");
  }

  @Test
  void slicesProgressiveFramesUsingTheChunkTables() {
    byte[] payload = {1, 2, 3, 4, 5, 6, 7};
    byte[] data = progressive(payload, 0, new int[] {3, 4}, new long[] {0}, new int[][] {{1, 2}});

    List<byte[]> frames = Mp4Aac.progressiveFrames(data);

    assertThat(frames).hasSize(2);
    assertThat(frames.get(0)).isEqualTo(slice(payload, 0, 3));
    assertThat(frames.get(1)).isEqualTo(slice(payload, 3, 4));
  }

  @Test
  void readsProgressiveFramesWithAUniformSampleSize() {
    byte[] payload = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    byte[] data = progressive(payload, 5, new int[] {5, 5}, new long[] {0}, new int[][] {{1, 2}});

    List<byte[]> frames = Mp4Aac.progressiveFrames(data);

    assertThat(frames).hasSize(2);
    assertThat(frames.get(0)).isEqualTo(slice(payload, 0, 5));
    assertThat(frames.get(1)).isEqualTo(slice(payload, 5, 5));
  }

  @Test
  void readsProgressiveFramesWithWideChunkOffsets() {
    byte[] payload = {1, 2, 3, 4};
    byte[] data =
        progressive(payload, 0, new int[] {2, 2}, new long[] {0}, new int[][] {{1, 2}}, true);

    assertThat(Mp4Aac.progressiveFrames(data)).hasSize(2);
  }

  @Test
  void usesEachSampleToChunkRunForTheChunksItCovers() {
    byte[] payload = {1, 2, 3, 4, 5, 6};
    byte[] data =
        progressive(
            payload, 0, new int[] {2, 2, 2}, new long[] {0, 2, 4}, new int[][] {{1, 1}, {2, 2}});

    List<byte[]> frames = Mp4Aac.progressiveFrames(data);

    assertThat(frames).hasSize(3);
    assertThat(frames.get(0)).isEqualTo(new byte[] {1, 2});
    assertThat(frames.get(2)).isEqualTo(new byte[] {5, 6});
  }

  @Test
  void stopsWhenAProgressiveSampleRunsPastTheFile() {
    byte[] data =
        progressive(new byte[] {1, 2, 3}, 0, new int[] {5}, new long[] {0}, new int[][] {{1, 1}});

    assertThat(Mp4Aac.progressiveFrames(data)).isEmpty();
  }

  @Test
  void rejectsProgressiveAudioWithoutSampleSizes() {
    byte[] stbl = box("stbl", concat(stco(new long[] {0}), stsc(new int[][] {{1, 1}})));
    byte[] data = box("moov", box("trak", box("mdia", box("minf", stbl))));

    assertThatThrownBy(() -> Mp4Aac.progressiveFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sample sizes");
  }

  @Test
  void rejectsProgressiveAudioWithoutChunkOffsets() {
    byte[] stbl = box("stbl", concat(stsz(0, new int[] {1}), stsc(new int[][] {{1, 1}})));
    byte[] data = box("moov", box("trak", box("mdia", box("minf", stbl))));

    assertThatThrownBy(() -> Mp4Aac.progressiveFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("chunk offsets");
  }

  @Test
  void rejectsProgressiveAudioWithoutAChunkMap() {
    byte[] stbl = box("stbl", concat(stsz(0, new int[] {1}), stco(new long[] {0})));
    byte[] data = box("moov", box("trak", box("mdia", box("minf", stbl))));

    assertThatThrownBy(() -> Mp4Aac.progressiveFrames(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("chunk map");
  }

  @Test
  void readsNoBoxesWhenTheRangeCannotHoldAHeader() {
    assertThat(Mp4Aac.boxes(new byte[4], 0, 4)).isEmpty();
  }

  @Test
  void rejectsAudioWithoutAMoovPath() {
    byte[] data = box("ftyp", ints(0));

    assertThatThrownBy(() -> Mp4Aac.audioSpecificConfig(data))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no sample description");
  }

  @Test
  void usesTheMediaDataStartWhenTheSampleTableHasNoDataOffset() {
    byte[] data = fragment(0x000200, new int[] {2, 3});

    assertThat(Mp4Aac.fragmentFrames(data)).hasSize(2);
  }

  @Test
  void readsNoFramesWhenTheSampleTableHasNoSizes() {
    byte[] data = fragment(0x000001, new int[] {0});

    assertThat(Mp4Aac.fragmentFrames(data)).isEmpty();
  }

  private static byte[] audioConfigFile(byte[] config) {
    byte[] stsd = box("stsd", concat(ints(0), ints(1), sampleEntry(config)));
    return concat(
        box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd))))),
        box("mdat", new byte[0]));
  }

  private static byte[] sampleEntry(byte[] config) {
    byte[] esdsPayload =
        concat(
            ints(0),
            descriptor(
                0x03,
                concat(
                    new byte[3],
                    descriptor(0x04, concat(new byte[13], descriptor(0x05, config))))));
    return box("mp4a", concat(new byte[28], box("esds", esdsPayload)));
  }

  private static byte[] fragment(int flags, int[] sizes) {
    int length = 0;
    for (int size : sizes) {
      length += size;
    }
    return fragment(flags, sizes, length);
  }

  private static byte[] fragment(int flags, int[] sizes, int mediaLength) {
    byte[] provisional = trun(flags, sizes, 0);
    int moofSize = 8 + 8 + provisional.length;
    byte[] trun = trun(flags, sizes, moofSize + 8);
    byte[] moof = box("moof", box("traf", trun));
    return concat(moof, box("mdat", mediaData(mediaLength)));
  }

  private static byte[] trun(int flags, int[] sizes, int dataOffset) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(ints(flags));
    payload.writeBytes(ints(sizes.length));
    if ((flags & 0x000001) != 0) {
      payload.writeBytes(ints(dataOffset));
    }
    if ((flags & 0x000004) != 0) {
      payload.writeBytes(ints(7));
    }
    for (int size : sizes) {
      if ((flags & 0x000100) != 0) {
        payload.writeBytes(ints(1024));
      }
      if ((flags & 0x000200) != 0) {
        payload.writeBytes(ints(size));
      } else {
        payload.writeBytes(ints(0));
      }
      if ((flags & 0x000400) != 0) {
        payload.writeBytes(ints(1));
      }
      if ((flags & 0x000800) != 0) {
        payload.writeBytes(ints(0));
      }
    }
    return box("trun", payload.toByteArray());
  }

  private static byte[] mediaData(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) (i + 1);
    }
    return data;
  }

  private static byte[] mdatPayload(byte[] data) {
    List<Mp4Aac.Box> boxes = Mp4Aac.boxes(data, 0, data.length);
    Mp4Aac.Box mdat =
        boxes.stream().filter(box -> box.type().equals("mdat")).findFirst().orElseThrow();
    return slice(data, mdat.payload(), mdat.size() - 8);
  }

  private static byte[] progressive(
      byte[] payload, int uniform, int[] sizes, long[] relativeOffsets, int[][] runs) {
    return progressive(payload, uniform, sizes, relativeOffsets, runs, false);
  }

  private static byte[] progressive(
      byte[] payload,
      int uniform,
      int[] sizes,
      long[] relativeOffsets,
      int[][] runs,
      boolean wide) {
    byte[] stco = stco(wide, new long[relativeOffsets.length]);
    byte[] stbl = box("stbl", concat(stsz(uniform, sizes), stco, stsc(runs)));
    byte[] moov = box("moov", box("trak", box("mdia", box("minf", stbl))));
    long mediaStart = moov.length + 8;
    long[] offsets = new long[relativeOffsets.length];
    for (int i = 0; i < relativeOffsets.length; i++) {
      offsets[i] = mediaStart + relativeOffsets[i];
    }
    byte[] realStco = stco(wide, offsets);
    byte[] realStbl = box("stbl", concat(stsz(uniform, sizes), realStco, stsc(runs)));
    byte[] realMoov = box("moov", box("trak", box("mdia", box("minf", realStbl))));
    return concat(realMoov, box("mdat", payload));
  }

  private static byte[] stsz(int uniform, int[] sizes) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(ints(0));
    payload.writeBytes(ints(uniform));
    payload.writeBytes(ints(sizes.length));
    if (uniform == 0) {
      for (int size : sizes) {
        payload.writeBytes(ints(size));
      }
    }
    return box("stsz", payload.toByteArray());
  }

  private static byte[] stco(long[] offsets) {
    return stco(false, offsets);
  }

  private static byte[] stco(boolean wide, long[] offsets) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(ints(0));
    payload.writeBytes(ints(offsets.length));
    for (long offset : offsets) {
      payload.writeBytes(wide ? longs(offset) : ints((int) offset));
    }
    return box(wide ? "co64" : "stco", payload.toByteArray());
  }

  private static byte[] stsc(int[][] runs) {
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(ints(0));
    payload.writeBytes(ints(runs.length));
    for (int[] run : runs) {
      payload.writeBytes(ints(run[0]));
      payload.writeBytes(ints(run[1]));
      payload.writeBytes(ints(1));
    }
    return box("stsc", payload.toByteArray());
  }

  private static byte[] descriptor(int tag, byte[] payload) {
    return concat(new byte[] {(byte) tag, (byte) payload.length}, payload);
  }

  private static byte[] box(String type, byte[] payload) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ints(payload.length + 8));
    out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(payload);
    return out.toByteArray();
  }

  private static byte[] ints(int... values) {
    ByteBuffer buffer = ByteBuffer.allocate(values.length * Integer.BYTES);
    for (int value : values) {
      buffer.putInt(value);
    }
    return buffer.array();
  }

  private static byte[] longs(long value) {
    return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
  }

  private static byte[] slice(byte[] data, int offset, int length) {
    byte[] slice = new byte[length];
    System.arraycopy(data, offset, slice, 0, length);
    return slice;
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }
}
