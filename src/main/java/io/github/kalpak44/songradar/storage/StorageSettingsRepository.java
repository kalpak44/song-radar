package io.github.kalpak44.songradar.storage;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists the singleton storage quota without coupling filesystem metrics to an entity. */
@Repository
public class StorageSettingsRepository {

  private final JdbcClient jdbc;

  public StorageSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Long> findLimitBytes() {
    return jdbc.sql("select limit_bytes from storage_settings where id = 1")
        .query(Long.class)
        .optional();
  }

  public void saveLimitBytes(long limitBytes) {
    jdbc.sql(
            """
            insert into storage_settings (id, limit_bytes)
            values (1, :limitBytes)
            on conflict (id) do update
            set limit_bytes = excluded.limit_bytes,
                updated_at = now()
            """)
        .param("limitBytes", limitBytes)
        .update();
  }
}
