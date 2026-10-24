package io.github.kalpak44.songradar.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.UncheckedIOException;
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

  @Test
  void saveRejectsAValueOutsideTheSupportedRange() {
    BigDecimal tooLarge = new BigDecimal("999999999999999999999999999999");

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.saveLimit(tooLarge));

    assertEquals("Enter a valid storage limit.", failure.getMessage());
  }

  @Test
  void saveRejectsAZeroLimit() {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.saveLimit(BigDecimal.ZERO));

    assertEquals("The storage limit must be greater than zero.", failure.getMessage());
  }

  @Test
  void snapshotWrapsFilesystemFailures() throws Exception {
    Path fileInsteadOfDirectory = Files.writeString(root.resolve("not-a-directory"), "x");
    StorageService invalidRoot = new StorageService(fileInsteadOfDirectory, settings);

    UncheckedIOException failure = assertThrows(UncheckedIOException.class, invalidRoot::snapshot);

    assertEquals("could not inspect the SongRadar storage volume", failure.getMessage());
  }
}
