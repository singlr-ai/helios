/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.dbclient.DbClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/** Shared PostgreSQL test container and Helidon DbClient setup. */
final class PgTestSupport {

  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static final DbClient DB_CLIENT;

  static {
    CONTAINER.start();
    DB_CLIENT = createDbClient();
    initSchema();
  }

  private PgTestSupport() {}

  static DbClient dbClient() {
    return DB_CLIENT;
  }

  static void truncate() {
    dbClient().execute().dml("TRUNCATE TABLE helios_prompts");
  }

  static void truncateTraces() {
    dbClient().execute().dml("TRUNCATE TABLE helios_traces CASCADE");
    dbClient().execute().dml("TRUNCATE TABLE helios_annotations");
  }

  static void truncateMemory() {
    dbClient().execute().dml("TRUNCATE TABLE helios_messages");
    dbClient().execute().dml("TRUNCATE TABLE helios_archive");
  }

  private static void initSchema() {
    try {
      var schema =
          new String(
              PgTestSupport.class.getResourceAsStream("schema.sql").readAllBytes(),
              StandardCharsets.UTF_8);
      for (var statement : schema.split(";")) {
        var trimmed = statement.strip();
        if (!trimmed.isEmpty()) {
          DB_CLIENT.execute().dml(trimmed);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to initialize schema", e);
    }
  }

  private static DbClient createDbClient() {
    var config =
        Config.builder()
            .addSource(
                ConfigSources.create(
                    Map.of(
                        "source", "jdbc",
                        "connection.url", CONTAINER.getJdbcUrl(),
                        "connection.username", CONTAINER.getUsername(),
                        "connection.password", CONTAINER.getPassword())))
            .disableEnvironmentVariablesSource()
            .disableSystemPropertiesSource()
            .build();
    return DbClient.create(config);
  }
}
