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
    service = new StorageService(root, BigDecimal.ZERO, settings);
  }

  @Test
  void snapshotReadsTheVolumeAndCountsSongRadarFiles() throws Exception {
    Files.write(root.resolve("one.m3u"), new byte[7]);
    Files.createDirectory(root.resolve("music"));
    Files.write(root.resolve("music/two.mp3"), new byte[11]);
    StorageSnapshot snapshot = service.snapshot();

    assertEquals(root.toAbsolutePath(), snapshot.path());
    assertTrue(snapshot.totalBytes() > 0);
    assertEquals(18, snapshot.songRadarBytes());
  }

  @Test
  void logicalCapacityIgnoresTheBackingFilesystemSize() throws Exception {
    Files.write(root.resolve("song.mp3"), new byte[11]);
    service = new StorageService(root, new BigDecimal("30"), settings);

    StorageSnapshot snapshot = service.snapshot();

    assertEquals(30_000_000_000L, snapshot.totalBytes());
    assertEquals(11, snapshot.usedBytes());
    assertEquals(29_999_999_989L, snapshot.availableBytes());
    assertTrue(snapshot.logicalCapacity());
  }

  @Test
  void savesAndValidatesRecordingRetention() {
    service.saveRecordsPerListener(12);
    verify(settings).saveRecordsPerListener(12);

    assertThrows(IllegalArgumentException.class, () -> service.saveRecordsPerListener(0));
    assertThrows(IllegalArgumentException.class, () -> service.saveRecordsPerListener(1001));
  }

  @Test
  void snapshotWrapsFilesystemFailures() throws Exception {
    Path fileInsteadOfDirectory = Files.writeString(root.resolve("not-a-directory"), "x");
    StorageService invalidRoot =
        new StorageService(fileInsteadOfDirectory, BigDecimal.ZERO, settings);

    UncheckedIOException failure = assertThrows(UncheckedIOException.class, invalidRoot::snapshot);

    assertEquals("could not inspect the SongRadar storage volume", failure.getMessage());
  }

  @Test
  void readsTheRetentionLimitFromTheSettingsRepository() {
    when(settings.findRecordsPerListener()).thenReturn(12);

    assertEquals(12, service.recordsPerListener());
  }

  @Test
  void rejectsNegativeAndOverflowingLogicalCapacities() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageService(root, new BigDecimal("-1"), settings));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageService(root, new BigDecimal("999999999999999999999999999"), settings));
  }
}
