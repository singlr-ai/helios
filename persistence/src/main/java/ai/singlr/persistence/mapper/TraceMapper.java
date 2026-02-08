/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.trace.Trace;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Maps Helidon {@link DbRow} results to {@link Trace} records. */
public final class TraceMapper {

  private TraceMapper() {}

  /**
   * Maps a single database row to a Trace (without spans).
   *
   * <p>Spans are assembled separately via tree reconstruction in PgTraceStore.
   */
  public static Trace map(DbRow row) {
    var attributes = JsonbMapper.fromJsonb(row.column("attributes").getString());

    return Trace.newBuilder()
        .withId(row.column("id").get(UUID.class))
        .withName(row.column("name").getString())
        .withStartTime(row.column("start_time").get(OffsetDateTime.class))
        .withEndTime(row.column("end_time").get(OffsetDateTime.class))
        .withError(row.column("error").getString())
        .withAttributes(attributes)
        .build();
  }
}
