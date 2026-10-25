package io.github.kalpak44.songradar.storage;

import java.nio.file.Path;

/** One point-in-time view of the configured SongRadar volume. */
public record StorageSnapshot(
    Path path,
    long totalBytes,
    long usedBytes,
    long availableBytes,
    long songRadarBytes,
    boolean logicalCapacity) {}
