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
      INSERT INTO helios_archive (id, agent_id, content, metadata, created_at)
      VALUES (CAST(? AS UUID), ?, ?, CAST(? AS JSONB), ?)
      """;

  public static final String SEARCH =
      """
      SELECT id, agent_id, content, metadata, created_at
      FROM helios_archive
      WHERE agent_id = ? AND content ILIKE ?
      ORDER BY created_at DESC
      LIMIT ?
      """;

  public static final String SEARCH_ALL =
      """
      SELECT id, agent_id, content, metadata, created_at
      FROM helios_archive
      WHERE agent_id = ?
      ORDER BY created_at DESC
      LIMIT ?
      """;
}
