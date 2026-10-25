package io.github.kalpak44.songradar.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StorageService {

  public static final long BYTES_PER_GB = 1_000_000_000L;
  private static final BigDecimal BYTES_PER_GB_DECIMAL = BigDecimal.valueOf(BYTES_PER_GB);

  private final Path root;
  private final StorageSettingsRepository settings;
  private final long logicalCapacityBytes;

  public StorageService(
      @Value("${songradar.storage.path}") Path root,
      @Value("${songradar.storage.capacity-gb:0}") BigDecimal logicalCapacityGb,
      StorageSettingsRepository settings) {
    this.root = root.toAbsolutePath().normalize();
    this.settings = settings;
    this.logicalCapacityBytes = capacityBytes(logicalCapacityGb);
  }

  public StorageSnapshot snapshot() {
    try {
      Files.createDirectories(root);
      long songRadar = directorySize(root);
      boolean logicalCapacity = logicalCapacityBytes > 0;
      long total;
      long used;
      long available;
      if (logicalCapacity) {
        total = logicalCapacityBytes;
        used = songRadar;
        available = Math.max(0, total - songRadar);
      } else {
        FileStore fileStore = Files.getFileStore(root);
        total = fileStore.getTotalSpace();
        available = fileStore.getUsableSpace();
        used = Math.max(0, total - available);
      }
      return new StorageSnapshot(root, total, used, available, songRadar, logicalCapacity);
    } catch (IOException e) {
      throw new UncheckedIOException("could not inspect the SongRadar storage volume", e);
    }
  }

  public int recordsPerListener() {
    return settings.findRecordsPerListener();
  }

  public void saveRecordsPerListener(int count) {
    if (count < 1 || count > 1000) {
      throw new IllegalArgumentException("Keep between 1 and 1,000 recordings per listener.");
    }
    settings.saveRecordsPerListener(count);
  }

  private static long directorySize(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(StorageService::sizeOrZero)
          .reduce(0L, StorageService::saturatedAdd);
    }
  }

  private static long capacityBytes(BigDecimal capacityGb) {
    if (capacityGb == null || capacityGb.signum() == 0) {
      return 0;
    }
    if (capacityGb.signum() < 0) {
      throw new IllegalArgumentException("Storage capacity cannot be negative.");
    }
    try {
      return capacityGb
          .multiply(BYTES_PER_GB_DECIMAL)
          .setScale(0, RoundingMode.HALF_UP)
          .longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Storage capacity is too large.", e);
    }
  }

  private static long sizeOrZero(Path path) {
    try {
      return Files.size(path);
    } catch (IOException _) {
      return 0;
    }
  }

  private static long saturatedAdd(long left, long right) {
    return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
  }
}
