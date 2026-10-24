package io.github.kalpak44.songradar.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;

class DatabaseStartupCheckTest {

  @Test
  void runBorrowsAConnectionAndLogsTheVersion() {
    JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    when(jdbcClient.sql("select version()").query(String.class).single())
        .thenReturn("PostgreSQL 16.4");
    clearInvocations(jdbcClient);

    assertDoesNotThrow(
        () -> new DatabaseStartupCheck(jdbcClient).run(new DefaultApplicationArguments()),
        "run should not throw when the version query succeeds");

    verify(jdbcClient).sql("select version()");
  }
}
