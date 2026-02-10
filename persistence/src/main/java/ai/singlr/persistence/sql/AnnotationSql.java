/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.sql;

/** SQL constants for annotation operations. */
public final class AnnotationSql {

  private AnnotationSql() {}

  public static final String INSERT =
      """
      INSERT INTO %s.helios_annotations (id, target_id, label, rating, comment, created_at)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?)
      """;

  public static final String FIND_BY_TARGET_ID =
      """
      SELECT id, target_id, label, rating, comment, created_at
      FROM %s.helios_annotations
      WHERE target_id = CAST(? AS UUID)
      ORDER BY created_at ASC
      """;
}
