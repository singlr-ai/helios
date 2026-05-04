/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.core.memory.ArchivalEntry;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.memory.MemoryBlock;
import ai.singlr.core.model.Message;
import ai.singlr.persistence.mapper.ArchiveMapper;
import ai.singlr.persistence.mapper.CoreBlockMapper;
import ai.singlr.persistence.mapper.JsonbMapper;
import ai.singlr.persistence.mapper.MessageMapper;
import ai.singlr.persistence.sql.ArchiveSql;
import ai.singlr.persistence.sql.CoreBlockSql;
import ai.singlr.persistence.sql.MessageSql;
import ai.singlr.persistence.sql.SessionSql;
import ai.singlr.scimsql.ScimEngine;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * PostgreSQL-backed implementation of {@link Memory} using Helidon DbClient.
 *
 * <p>All three memory tiers persist:
 *
 * <ul>
 *   <li><b>Core blocks</b> — agent-scoped, always-in-context. Keyed by {@code (agent_id,
 *       block_name)}; survive across {@code Agent.run} invocations and JVM restarts. Per-user
 *       isolation is achieved by namespacing the {@code agentId} (e.g. {@code
 *       "kubera-research:user-42"}).
 *   <li><b>Archival</b> — agent-scoped long-term storage with SCIM-filtered search.
 *   <li><b>Session history + registry</b> — session-scoped conversation turns.
 * </ul>
 *
 * <p>Search methods interpret the query parameter as a SCIM filter (RFC 7644) via scim-sql.
 */
public class PgMemory implements Memory {

  private final PgConfig config;
  private final DbClient dbClient;
  private final String agentId;

  public PgMemory(PgConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.dbClient = config.dbClient();
    this.agentId = Objects.requireNonNull(config.agentId(), "agentId");
  }

  @Override
  public List<MemoryBlock> coreBlocks() {
    try {
      return CoreBlockMapper.mapAll(
          dbClient.execute().query(config.qualify(CoreBlockSql.FIND_ALL), agentId));
    } catch (Exception e) {
      throw new PgException("Failed to load core blocks for agent: " + agentId, e);
    }
  }

  @Override
  public MemoryBlock block(String name) {
    if (name == null) {
      return null;
    }
    try {
      return dbClient
          .execute()
          .query(config.qualify(CoreBlockSql.FIND_BY_NAME), agentId, name)
          .map(CoreBlockMapper::map)
          .findFirst()
          .orElse(null);
    } catch (Exception e) {
      throw new PgException("Failed to load core block: " + name, e);
    }
  }

  @Override
  public void putBlock(MemoryBlock block) {
    Objects.requireNonNull(block, "block");
    try {
      dbClient
          .execute()
          .dml(
              config.qualify(CoreBlockSql.UPSERT),
              agentId,
              block.name(),
              block.id(),
              block.description(),
              JsonbMapper.objectToJsonb(block.data()),
              block.maxSize(),
              toOffset(block.createdAt()),
              toOffset(block.updatedAt()));
    } catch (Exception e) {
      throw new PgException("Failed to persist core block: " + block.name(), e);
    }
  }

  /**
   * Atomic single-key merge via JSONB's {@code ||} operator. Two concurrent calls updating
   * different keys on the same block both survive; matches the {@code computeIfPresent} contract in
   * {@link ai.singlr.core.memory.InMemoryMemory#updateBlock}. The previous read-then-write
   * implementation lost one update under contention.
   */
  @Override
  public void updateBlock(String blockName, String key, Object value) {
    if (Strings.isBlank(blockName)) {
      throw new IllegalArgumentException("blockName must not be blank");
    }
    if (Strings.isBlank(key)) {
      throw new IllegalArgumentException("key must not be blank");
    }
    long affected;
    try {
      var patch = new HashMap<String, Object>();
      patch.put(key, value);
      affected =
          dbClient
              .execute()
              .dml(
                  config.qualify(CoreBlockSql.MERGE_DATA),
                  JsonbMapper.objectToJsonb(patch),
                  Ids.now(),
                  agentId,
                  blockName);
    } catch (Exception e) {
      throw new PgException("Failed to update core block: " + blockName, e);
    }
    if (affected == 0) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
  }

  /**
   * Whole-data replacement. The DML rowcount is the source of truth for existence — no separate
   * SELECT, so no TOCTOU race between existence check and update.
   */
  @Override
  public void replaceBlock(String blockName, Map<String, Object> data) {
    if (Strings.isBlank(blockName)) {
      throw new IllegalArgumentException("blockName must not be blank");
    }
    Objects.requireNonNull(data, "data must not be null; pass an empty map to clear the block");
    long affected;
    try {
      affected =
          dbClient
              .execute()
              .dml(
                  config.qualify(CoreBlockSql.REPLACE_DATA),
                  JsonbMapper.objectToJsonb(data),
                  Ids.now(),
                  agentId,
                  blockName);
    } catch (Exception e) {
      throw new PgException("Failed to replace core block: " + blockName, e);
    }
    if (affected == 0) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
  }

  private static OffsetDateTime toOffset(java.time.Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @Override
  public void archive(String content, Map<String, Object> metadata) {
    try {
      var id = Ids.newId();
      var createdAt = Ids.now();
      dbClient
          .execute()
          .dml(
              config.qualify(ArchiveSql.INSERT),
              id.toString(),
              agentId,
              content,
              JsonbMapper.objectToJsonb(metadata),
              createdAt);
    } catch (Exception e) {
      throw new PgException("Failed to archive content", e);
    }
  }

  @Override
  public List<ArchivalEntry> searchArchive(String query, int limit) {
    try {
      if (query == null || query.isBlank()) {
        return ArchiveMapper.mapAll(
            dbClient.execute().query(config.qualify(ArchiveSql.FIND_ALL), agentId, limit));
      }
      return searchWithScim(
          query,
          config.qualify(ArchiveSql.SCIM_SELECT),
          "agent_id = :agentId",
          Map.of("agentId", agentId),
          ArchiveMapper::mapAll);
    } catch (Exception e) {
      throw new PgException("Failed to search archive", e);
    }
  }

  @Override
  public List<Message> history(String userId, UUID sessionId) {
    try {
      return MessageMapper.mapAll(
          dbClient
              .execute()
              .query(config.qualify(MessageSql.FIND_BY_SESSION), sessionId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to get history for session: " + sessionId, e);
    }
  }

  @Override
  public void addMessage(String userId, UUID sessionId, Message message) {
    try {
      var id = Ids.newId();
      var createdAt = Ids.now();
      dbClient
          .execute()
          .dml(
              config.qualify(MessageSql.INSERT),
              id.toString(),
              sessionId.toString(),
              message.role().name(),
              message.content(),
              message.hasToolCalls() ? JsonbMapper.objectToJsonb(message.toolCalls()) : null,
              message.toolCallId(),
              message.toolName(),
              JsonbMapper.toJsonb(message.metadata()),
              createdAt);
    } catch (Exception e) {
      throw new PgException("Failed to add message to session: " + sessionId, e);
    }
  }

  @Override
  public void clearHistory(String userId, UUID sessionId) {
    try {
      dbClient.execute().dml(config.qualify(MessageSql.DELETE_BY_SESSION), sessionId.toString());
    } catch (Exception e) {
      throw new PgException("Failed to clear history for session: " + sessionId, e);
    }
  }

  @Override
  public List<Message> searchHistory(String userId, UUID sessionId, String query, int limit) {
    try {
      if (query == null || query.isBlank()) {
        return MessageMapper.mapAll(
            dbClient
                .execute()
                .query(
                    config.qualify(MessageSql.FIND_BY_SESSION_LIMIT), sessionId.toString(), limit));
      }
      return searchWithScim(
          query,
          config.qualify(MessageSql.SCIM_SELECT),
          "session_id = CAST(:sessionId AS UUID)",
          Map.of("sessionId", sessionId.toString()),
          MessageMapper::mapAll);
    } catch (Exception e) {
      throw new PgException("Failed to search history for session: " + sessionId, e);
    }
  }

  @Override
  public void registerSession(String userId, UUID sessionId) {
    try {
      var now = Ids.now();
      dbClient
          .execute()
          .dml(config.qualify(SessionSql.UPSERT), sessionId.toString(), agentId, userId, now, now);
    } catch (Exception e) {
      throw new PgException("Failed to register session: " + sessionId, e);
    }
  }

  @Override
  public Optional<UUID> latestSession(String userId) {
    try {
      return dbClient
          .execute()
          .query(config.qualify(SessionSql.FIND_LATEST), agentId, userId)
          .map(row -> UUID.fromString(row.column("id").asString().orElseThrow()))
          .findFirst();
    } catch (Exception e) {
      throw new PgException("Failed to find latest session for user: " + userId, e);
    }
  }

  @Override
  public List<UUID> sessions(String userId) {
    try {
      return dbClient
          .execute()
          .query(config.qualify(SessionSql.FIND_BY_USER), agentId, userId)
          .map(row -> UUID.fromString(row.column("id").asString().orElseThrow()))
          .toList();
    } catch (Exception e) {
      throw new PgException("Failed to find sessions for user: " + userId, e);
    }
  }

  private <T> List<T> searchWithScim(
      String scimFilter,
      String selectFrom,
      String scopeClause,
      Map<String, Object> scopeParams,
      Function<Stream<DbRow>, List<T>> mapper) {
    var engine = new ScimEngine();
    var filter = engine.parseFilter(scimFilter, "", null);
    var clause = filter.toClause();
    var params = new HashMap<String, Object>(filter.context().indexedParams());
    params.putAll(scopeParams);

    var sql = selectFrom + " WHERE " + scopeClause + " AND (" + clause + ") ORDER BY id";

    return mapper.apply(dbClient.execute().createQuery(sql).params(params).execute());
  }
}
