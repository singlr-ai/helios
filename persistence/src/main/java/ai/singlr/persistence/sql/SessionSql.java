/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.persistence.sql;

/** SQL constants for session registry operations. */
public final class SessionSql {

  private SessionSql() {}

  public static final String UPSERT =
      """
      INSERT INTO helios_sessions (id, agent_id, user_id, created_at, last_active_at)
      VALUES (CAST(? AS UUID), ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET last_active_at = EXCLUDED.last_active_at
      """;

  public static final String FIND_LATEST =
      """
      SELECT id
      FROM helios_sessions
      WHERE agent_id = ? AND user_id = ?
      ORDER BY last_active_at DESC
      LIMIT 1
      """;

  public static final String FIND_BY_USER =
      """
      SELECT id
      FROM helios_sessions
      WHERE agent_id = ? AND user_id = ?
      ORDER BY last_active_at DESC
      """;
}
