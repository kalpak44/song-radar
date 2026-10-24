package io.github.kalpak44.songradar.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageServiceTest {

  @TempDir Path root;

  private StorageSettingsRepository settings;
  private StorageService service;

  @BeforeEach
  void setUp() {
    settings = mock(StorageSettingsRepository.class);
    service = new StorageService(root, settings);
  }

  @Test
  void snapshotReadsTheVolumeAndCountsSongRadarFiles() throws Exception {
    Files.write(root.resolve("one.m3u"), new byte[7]);
    Files.createDirectory(root.resolve("music"));
    Files.write(root.resolve("music/two.mp3"), new byte[11]);
    when(settings.findLimitBytes()).thenReturn(Optional.of(1_000L));

    StorageSnapshot snapshot = service.snapshot();

    assertEquals(root.toAbsolutePath(), snapshot.path());
    assertTrue(snapshot.totalBytes() > 0);
    assertEquals(18, snapshot.songRadarBytes());
    assertEquals(1_000, snapshot.limitBytes());
  }

  @Test
  void volumeCapacityIsTheDefaultLimit() {
    when(settings.findLimitBytes()).thenReturn(Optional.empty());

    StorageSnapshot snapshot = service.snapshot();

    assertEquals(snapshot.totalBytes(), snapshot.limitBytes());
  }

  @Test
  void saveConvertsDecimalGigabytesToBytes() {
    when(settings.findLimitBytes()).thenReturn(Optional.empty());

    service.saveLimit(new BigDecimal("0.1"));

    verify(settings).saveLimitBytes(100_000_000L);
  }

  @Test
  void saveRejectsAValueAboveTheVolumeCapacity() {
    when(settings.findLimitBytes()).thenReturn(Optional.empty());
    BigDecimal tooLarge =
        BigDecimal.valueOf(service.snapshot().totalBytes())
            .divide(BigDecimal.valueOf(StorageService.BYTES_PER_GB))
            .add(BigDecimal.ONE);

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.saveLimit(tooLarge));

    assertEquals("The storage limit cannot exceed the volume capacity.", failure.getMessage());
  }
}
