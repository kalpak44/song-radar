package io.github.kalpak44.songradar.storage;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists singleton recording-retention settings without coupling metrics to an entity. */
@Repository
public class StorageSettingsRepository {

  private final JdbcClient jdbc;

  public StorageSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int findRecordsPerListener() {
    return jdbc.sql("select records_per_listener from storage_settings where id = 1")
        .query(Integer.class)
        .optional()
        .orElse(20);
  }

  public void saveRecordsPerListener(int count) {
    jdbc.sql(
            """
            insert into storage_settings (id, records_per_listener)
            values (1, :count)
            on conflict (id) do update
            set records_per_listener = excluded.records_per_listener,
                updated_at = now()
            """)
        .param("count", count)
        .update();
  }
}
