/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for trace operations. */
public final class TraceSql {

  private TraceSql() {}

  public static final String INSERT =
      """
      INSERT INTO helios_traces (id, name, start_time, end_time, error, attributes)
      VALUES (CAST(? AS UUID), ?, ?, ?, ?, CAST(? AS JSONB))
      """;

  public static final String FIND_BY_ID =
      """
      SELECT id, name, start_time, end_time, error, attributes
      FROM helios_traces
      WHERE id = CAST(? AS UUID)
      """;
}
