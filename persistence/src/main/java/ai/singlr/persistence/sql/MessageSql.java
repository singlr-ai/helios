/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for session message operations. */
public final class MessageSql {

  private MessageSql() {}

  public static final String INSERT =
      """
      INSERT INTO helios_messages (id, session_id, role, content, tool_calls, tool_call_id, tool_name, metadata, created_at)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, CAST(? AS JSONB), ?, ?, CAST(? AS JSONB), ?)
      """;

  public static final String FIND_BY_SESSION =
      """
      SELECT id, session_id, role, content, tool_calls, tool_call_id, tool_name, metadata, created_at
      FROM helios_messages
      WHERE session_id = CAST(? AS UUID)
      ORDER BY id
      """;

  public static final String DELETE_BY_SESSION =
      """
      DELETE FROM helios_messages
      WHERE session_id = CAST(? AS UUID)
      """;

  public static final String FIND_BY_SESSION_LIMIT =
      """
      SELECT id, session_id, role, content, tool_calls, tool_call_id, tool_name, metadata, created_at
      FROM helios_messages
      WHERE session_id = CAST(? AS UUID)
      ORDER BY id
      LIMIT ?
      """;
}
