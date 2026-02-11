/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PgConfigTest {

  @Test
  void defaultSchemaIsPublic() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertEquals("public", config.schema());
  }

  @Test
  void customSchema() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("lg").build();

    assertEquals("lg", config.schema());
  }

  @Test
  void qualifyWithDefaultSchema() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertEquals(
        "SELECT * FROM public.helios_prompts WHERE name = ?",
        config.qualify("SELECT * FROM %s.helios_prompts WHERE name = ?"));
  }

  @Test
  void qualifyWithCustomSchema() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("myapp").build();

    assertEquals(
        "SELECT * FROM myapp.helios_prompts WHERE name = ?",
        config.qualify("SELECT * FROM %s.helios_prompts WHERE name = ?"));
  }

  @Test
  void qualifyMultiplePlaceholders() {
    var config =
        PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).withSchema("lg").build();

    assertEquals(
        "INSERT INTO lg.helios_spans SELECT * FROM lg.helios_traces",
        config.qualify("INSERT INTO %s.helios_spans SELECT * FROM %s.helios_traces"));
  }

  @Test
  void agentIdDefaultsToNull() {
    var config = PgConfig.newBuilder().withDbClient(PgTestSupport.dbClient()).build();

    assertNull(config.agentId());
  }

  @Test
  void agentIdIsStored() {
    var config =
        PgConfig.newBuilder()
            .withDbClient(PgTestSupport.dbClient())
            .withAgentId("my-agent")
            .build();

    assertEquals("my-agent", config.agentId());
  }

  @Test
  void nullDbClientThrows() {
    assertThrows(NullPointerException.class, () -> PgConfig.newBuilder().build());
  }
}
