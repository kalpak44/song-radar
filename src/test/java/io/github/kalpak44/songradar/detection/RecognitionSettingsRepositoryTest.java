package io.github.kalpak44.songradar.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class RecognitionSettingsRepositoryTest {

  @Test
  void readsTheStoredProvider() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    when(jdbc.sql(anyString()).query(String.class).optional()).thenReturn(Optional.of("shazam"));

    assertThat(new RecognitionSettingsRepository(jdbc).findProvider())
        .isEqualTo(RecognitionProvider.SHAZAM);
  }

  /**
   * An empty table means recognition has never been configured, which is not the same as AudD being
   * chosen — identifying nothing is the safe reading.
   */
  @Test
  void defaultsToDisabledWhenNoProviderHasBeenSaved() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    when(jdbc.sql(anyString()).query(String.class).optional()).thenReturn(Optional.empty());

    assertThat(new RecognitionSettingsRepository(jdbc).findProvider())
        .isEqualTo(RecognitionProvider.NONE);
  }

  @Test
  void savesTheProviderAsTheSingletonRow() {
    JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    new RecognitionSettingsRepository(jdbc).saveProvider(RecognitionProvider.SHAZAM);

    verify(jdbc).sql(anyString());
    verify(jdbc.sql(anyString())).param(eq("provider"), eq("shazam"));
  }
}
