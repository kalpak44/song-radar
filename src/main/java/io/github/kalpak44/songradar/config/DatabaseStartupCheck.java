package io.github.kalpak44.songradar.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Borrows a connection once at startup so a wrong host or password fails here, with the database's
 * own error, rather than on the first page that happens to read from it.
 */
@Component
public class DatabaseStartupCheck implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DatabaseStartupCheck.class);

  private final JdbcClient jdbcClient;

  public DatabaseStartupCheck(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public void run(ApplicationArguments args) {
    String version = jdbcClient.sql("select version()").query(String.class).single();
    log.info("Connected to {}", version);
  }
}
