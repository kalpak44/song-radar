package io.github.kalpak44.songradar.activity;

/** The steps a track passes through, from the stream to the library. */
public enum ActivityKind {
  RECORDED,
  DETECTED,
  NOT_IDENTIFIED,
  SEARCHING,
  SAVED,
  UNAVAILABLE
}
