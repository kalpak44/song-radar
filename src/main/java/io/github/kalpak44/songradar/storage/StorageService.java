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

  public StorageService(
      @Value("${songradar.storage.path}") Path root, StorageSettingsRepository settings) {
    this.root = root.toAbsolutePath().normalize();
    this.settings = settings;
  }

  public StorageSnapshot snapshot() {
    try {
      Files.createDirectories(root);
      FileStore fileStore = Files.getFileStore(root);
      long total = fileStore.getTotalSpace();
      long available = fileStore.getUsableSpace();
      long used = Math.max(0, total - available);
      long songRadar = directorySize(root);
      long configuredLimit = settings.findLimitBytes().orElse(total);
      long limit = Math.min(configuredLimit, total);
      return new StorageSnapshot(root, total, used, available, songRadar, limit);
    } catch (IOException e) {
      throw new UncheckedIOException("could not inspect the SongRadar storage volume", e);
    }
  }

  public void saveLimit(BigDecimal gigabytes) {
    long bytes;
    try {
      bytes =
          gigabytes
              .multiply(BYTES_PER_GB_DECIMAL)
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Enter a valid storage limit.", e);
    }

    long total = snapshot().totalBytes();
    if (bytes <= 0) {
      throw new IllegalArgumentException("The storage limit must be greater than zero.");
    }
    if (bytes > total) {
      throw new IllegalArgumentException("The storage limit cannot exceed the volume capacity.");
    }
    settings.saveLimitBytes(bytes);
  }

  private static long directorySize(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      return paths
          .filter(Files::isRegularFile)
          .mapToLong(StorageService::sizeOrZero)
          .reduce(0L, StorageService::saturatedAdd);
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
