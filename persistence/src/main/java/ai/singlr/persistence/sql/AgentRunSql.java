/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for {@code helios_agent_runs} operations. */
public final class AgentRunSql {

  private AgentRunSql() {}

  public static final String UPSERT =
      """
      INSERT INTO %s.helios_agent_runs (
          run_id, session_id, agent_id, user_id, status, iteration,
          started_at, last_checkpoint_at, ended_at, error)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (run_id) DO UPDATE SET
          session_id = EXCLUDED.session_id,
          agent_id = EXCLUDED.agent_id,
          user_id = EXCLUDED.user_id,
          status = EXCLUDED.status,
          iteration = EXCLUDED.iteration,
          last_checkpoint_at = EXCLUDED.last_checkpoint_at,
          ended_at = EXCLUDED.ended_at,
          error = EXCLUDED.error
      """;

  public static final String FIND_BY_ID =
      """
      SELECT run_id, session_id, agent_id, user_id, status, iteration,
             started_at, last_checkpoint_at, ended_at, error
      FROM %s.helios_agent_runs
      WHERE run_id = CAST(? AS UUID)
      """;

  public static final String FIND_BY_STATUS =
      """
      SELECT run_id, session_id, agent_id, user_id, status, iteration,
             started_at, last_checkpoint_at, ended_at, error
      FROM %s.helios_agent_runs
      WHERE status = ?
      ORDER BY last_checkpoint_at DESC
      """;
}
