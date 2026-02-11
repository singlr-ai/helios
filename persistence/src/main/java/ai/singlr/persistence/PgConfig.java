/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import io.helidon.dbclient.DbClient;
import java.util.Objects;

/**
 * Shared configuration for all PostgreSQL persistence classes.
 *
 * @param dbClient the Helidon DbClient for database access
 * @param schema the PostgreSQL schema name (defaults to {@code "public"})
 * @param agentId the agent identifier for scoping data, or null when not needed
 */
public record PgConfig(DbClient dbClient, String schema, String agentId) {

  public PgConfig {
    Objects.requireNonNull(dbClient, "dbClient");
    if (schema == null) schema = "public";
  }

  /**
   * Qualify a SQL string by replacing {@code %s} placeholders with the schema name.
   *
   * <p>SQL constants use {@code %s.helios_*} for table references. This method substitutes the
   * configured schema, producing e.g. {@code public.helios_prompts} or {@code lg.helios_prompts}.
   */
  public String qualify(String sql) {
    return sql.replace("%s", schema);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for PgConfig. */
  public static class Builder {

    private DbClient dbClient;
    private String schema;
    private String agentId;

    private Builder() {}

    public Builder withDbClient(DbClient dbClient) {
      this.dbClient = dbClient;
      return this;
    }

    public Builder withSchema(String schema) {
      this.schema = schema;
      return this;
    }

    public Builder withAgentId(String agentId) {
      this.agentId = agentId;
      return this;
    }

    public PgConfig build() {
      return new PgConfig(dbClient, schema, agentId);
    }
  }
}
