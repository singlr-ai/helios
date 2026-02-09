/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.memory.ArchivalEntry;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Maps Helidon {@link DbRow} results to {@link ArchivalEntry} records. */
public final class ArchiveMapper {

  private ArchiveMapper() {}

  /** Maps a single database row to an ArchivalEntry. */
  public static ArchivalEntry map(DbRow row) {
    return new ArchivalEntry(
        row.column("id").get(UUID.class).toString(),
        row.column("content").getString(),
        JsonbMapper.fromJsonbObject(row.column("metadata").getString()),
        row.column("created_at").get(OffsetDateTime.class).toInstant());
  }

  /** Maps a stream of database rows to an immutable list of ArchivalEntries. */
  public static List<ArchivalEntry> mapAll(Stream<DbRow> rows) {
    return rows.map(ArchiveMapper::map).toList();
  }
}
