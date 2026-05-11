/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.memory.MemoryBlock;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * Maps Helidon {@link DbRow} results to {@link MemoryBlock} records.
 *
 * <p>The {@code block_id} column is kept on the {@code helios_core_blocks} table (1.2-vintage DDL)
 * as a server-generated primary key, but is no longer surfaced on the Java {@link MemoryBlock}
 * record — lookups are by {@code (agent_id, block_name)}. The column exists only as an internal
 * identifier; operators may drop it in a future minor. The mapper intentionally ignores it.
 */
public final class CoreBlockMapper {

  private CoreBlockMapper() {}

  /** Maps a single database row to a {@link MemoryBlock}. */
  public static MemoryBlock map(DbRow row) {
    return new MemoryBlock(
        row.column("block_name").getString(),
        row.column("description").as(String.class).orElse(null),
        JsonbMapper.fromJsonbObject(row.column("data").getString()),
        row.column("max_size").getInt(),
        row.column("created_at").get(OffsetDateTime.class).toInstant(),
        row.column("updated_at").get(OffsetDateTime.class).toInstant());
  }

  /** Maps a stream of database rows to an immutable list of {@link MemoryBlock} records. */
  public static List<MemoryBlock> mapAll(Stream<DbRow> rows) {
    return rows.map(CoreBlockMapper::map).toList();
  }
}
