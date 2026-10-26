package io.github.kalpak44.songradar.detection;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persists the singleton choice of recognition provider. */
@Repository
public class RecognitionSettingsRepository {

  private final JdbcClient jdbc;

  public RecognitionSettingsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public RecognitionProvider findProvider() {
    return jdbc.sql("select provider from recognition_settings where id = 1")
        .query(String.class)
        .optional()
        .map(RecognitionProvider::of)
        .orElse(RecognitionProvider.NONE);
  }

  public void saveProvider(RecognitionProvider provider) {
    jdbc.sql(
            """
            insert into recognition_settings (id, provider)
            values (1, :provider)
            on conflict (id) do update
            set provider = excluded.provider,
                updated_at = now()
            """)
        .param("provider", provider.id())
        .update();
  }
}
