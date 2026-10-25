package io.github.kalpak44.songradar.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class StorageSettingsRepositoryTest {

  @Test
  void readsTheStoredRetentionLimit() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    when(jdbc.sql(anyString()).query(Integer.class).optional()).thenReturn(Optional.of(12));

    assertEquals(12, new StorageSettingsRepository(jdbc).findRecordsPerListener());
  }

  @Test
  void defaultsToTwentyWhenNoSettingHasBeenSaved() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    when(jdbc.sql(anyString()).query(Integer.class).optional()).thenReturn(Optional.empty());

    assertEquals(20, new StorageSettingsRepository(jdbc).findRecordsPerListener());
  }

  @Test
  void savesTheRetentionLimitAsTheSingletonRow() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    new StorageSettingsRepository(jdbc).saveRecordsPerListener(7);

    verify(jdbc).sql(anyString());
  }
}
