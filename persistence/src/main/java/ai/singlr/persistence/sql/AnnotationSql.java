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
      INSERT INTO %s.helios_annotations (id, target_id, label, rating, comment, created_at, author_id)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?)
      """;

  public static final String UPSERT =
      """
      INSERT INTO %s.helios_annotations (id, target_id, label, rating, comment, created_at, author_id)
      VALUES (CAST(? AS UUID), CAST(? AS UUID), ?, ?, ?, ?, ?)
      ON CONFLICT (target_id, author_id) WHERE author_id IS NOT NULL
      DO UPDATE SET label = EXCLUDED.label, rating = EXCLUDED.rating,
          comment = EXCLUDED.comment, created_at = EXCLUDED.created_at
      """;

  public static final String FIND_BY_TARGET_ID =
      """
      SELECT id, target_id, label, rating, comment, created_at, author_id
      FROM %s.helios_annotations
      WHERE target_id = CAST(? AS UUID)
      ORDER BY created_at ASC
      """;
}
