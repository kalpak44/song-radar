package io.github.kalpak44.songradar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kalpak44.songradar.storage.StorageSnapshot;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StorageViewTest {

  @Test
  void formatsCapacityAndCalculatesDiskSegments() {
    StorageView view =
        StorageView.of(
            new StorageSnapshot(
                Path.of("/data"),
                100_000_000_000L,
                35_000_000_000L,
                65_000_000_000L,
                12_000_000_000L,
                20_000_000_000L));

    assertEquals("100 GB", view.total());
    assertEquals("35.0 GB", view.used());
    assertEquals("12", view.songRadarGb());
    assertEquals("12.0", view.songRadarPercent());
    assertEquals("23.0", view.otherUsedPercent());
    assertEquals("20.0", view.limitPercent());
  }

  @Test
  void reportsWhenExistingFilesExceedTheLimit() {
    StorageView view =
        StorageView.of(
            new StorageSnapshot(
                Path.of("/data"),
                2_000_000_000_000L,
                500_000_000_000L,
                1_500_000_000_000L,
                30_000_000_000L,
                20_000_000_000L));

    assertEquals("2.0 TB", view.total());
    assertTrue(view.exceedsLimit());
  }

  @Test
  void anEmptyVolumeReportsZeroPercentages() {
    StorageView view = StorageView.of(new StorageSnapshot(Path.of("/data"), 0L, 0L, 0L, 0L, 0L));

    assertEquals("0", view.usedPercent());
    assertEquals("0", view.songRadarPercent());
    assertEquals("0", view.otherUsedPercent());
    assertEquals("0", view.limitPercent());
  }
}
