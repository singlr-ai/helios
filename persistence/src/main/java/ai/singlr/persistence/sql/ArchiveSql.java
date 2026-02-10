/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for archival memory operations. */
public final class ArchiveSql {

  private ArchiveSql() {}

  public static final String INSERT =
      """
      INSERT INTO %s.helios_archive (id, agent_id, content, metadata, created_at)
      VALUES (CAST(? AS UUID), ?, ?, CAST(? AS JSONB), ?)
      """;

  /** SELECT prefix for SCIM-filtered queries (WHERE clause appended dynamically). */
  public static final String SCIM_SELECT =
      "SELECT id, agent_id, content, metadata, created_at FROM %s.helios_archive";

  public static final String FIND_ALL =
      """
      SELECT id, agent_id, content, metadata, created_at
      FROM %s.helios_archive
      WHERE agent_id = ?
      ORDER BY created_at DESC
      LIMIT ?
      """;
}
