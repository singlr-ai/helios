/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.persistence.sql;

/** SQL constants for {@code helios_core_blocks} — agent-scoped always-in-context memory blocks. */
public final class CoreBlockSql {

  private CoreBlockSql() {}

  /**
   * Upsert by {@code (agent_id, block_name)}. On conflict, refreshes {@code data}, {@code
   * description}, {@code max_size}, and {@code updated_at} but preserves {@code block_id} and
   * {@code created_at}.
   */
  public static final String UPSERT =
      """
      INSERT INTO %s.helios_core_blocks
        (agent_id, block_name, block_id, description, data, max_size, created_at, updated_at)
      VALUES (?, ?, CAST(? AS UUID), ?, CAST(? AS JSONB), ?, ?, ?)
      ON CONFLICT (agent_id, block_name) DO UPDATE SET
        description = EXCLUDED.description,
        data        = EXCLUDED.data,
        max_size    = EXCLUDED.max_size,
        updated_at  = EXCLUDED.updated_at
      """;

  public static final String FIND_ALL =
      """
      SELECT agent_id, block_name, block_id, description, data, max_size, created_at, updated_at
      FROM %s.helios_core_blocks
      WHERE agent_id = ?
      ORDER BY block_name
      """;

  public static final String FIND_BY_NAME =
      """
      SELECT agent_id, block_name, block_id, description, data, max_size, created_at, updated_at
      FROM %s.helios_core_blocks
      WHERE agent_id = ? AND block_name = ?
      """;

  /**
   * Atomic single-key merge using JSONB's {@code ||} operator. The supplied JSON object overwrites
   * matching top-level keys without touching others, so two concurrent updates against the same
   * block but different keys both survive — replacing the previous read-modify-write that lost one
   * update under contention.
   */
  public static final String MERGE_DATA =
      """
      UPDATE %s.helios_core_blocks
      SET data = data || CAST(? AS JSONB), updated_at = ?
      WHERE agent_id = ? AND block_name = ?
      """;

  /** Whole-data replacement; callers must check the rowcount to detect a missing block. */
  public static final String REPLACE_DATA =
      """
      UPDATE %s.helios_core_blocks
      SET data = CAST(? AS JSONB), updated_at = ?
      WHERE agent_id = ? AND block_name = ?
      """;
}
