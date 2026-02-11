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
    initTriggers();
  }

  private PgTestSupport() {}

  static DbClient dbClient() {
    return DB_CLIENT;
  }

  static PgConfig pgConfig() {
    return PgConfig.newBuilder().withDbClient(DB_CLIENT).build();
  }

  static PgConfig pgConfig(String agentId) {
    return PgConfig.newBuilder().withDbClient(DB_CLIENT).withAgentId(agentId).build();
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
    dbClient().execute().dml("TRUNCATE TABLE helios_sessions");
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

  private static void initTriggers() {
    DB_CLIENT
        .execute()
        .dml(
            """
            CREATE OR REPLACE FUNCTION helios_update_feedback_counts()
            RETURNS TRIGGER AS $$
            BEGIN
              IF NEW.rating > 0 THEN
                UPDATE helios_traces SET thumbs_up_count = thumbs_up_count + 1
                  WHERE id = NEW.target_id;
              ELSIF NEW.rating < 0 THEN
                UPDATE helios_traces SET thumbs_down_count = thumbs_down_count + 1
                  WHERE id = NEW.target_id;
              END IF;
              RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
    DB_CLIENT
        .execute()
        .dml(
            """
            CREATE OR REPLACE TRIGGER trg_helios_feedback_counts
              AFTER INSERT ON helios_annotations
              FOR EACH ROW
              WHEN (NEW.rating IS NOT NULL AND NEW.rating != 0)
              EXECUTE FUNCTION helios_update_feedback_counts()
            """);
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
