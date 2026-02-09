/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import ai.singlr.core.common.Ids;
import ai.singlr.core.memory.ArchivalEntry;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.memory.MemoryBlock;
import ai.singlr.core.model.Message;
import ai.singlr.persistence.mapper.ArchiveMapper;
import ai.singlr.persistence.mapper.JsonbMapper;
import ai.singlr.persistence.mapper.MessageMapper;
import ai.singlr.persistence.sql.ArchiveSql;
import ai.singlr.persistence.sql.MessageSql;
import ai.singlr.scimsql.ScimEngine;
import io.helidon.dbclient.DbClient;
import io.helidon.dbclient.DbRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * PostgreSQL-backed implementation of {@link Memory} using Helidon DbClient.
 *
 * <p>Supports archival memory (agent-scoped) and session history (session-scoped). Core memory
 * blocks are not yet supported and return no-ops. Search methods interpret the query parameter as a
 * SCIM filter (RFC 7644) via scim-sql.
 */
public class PgMemory implements Memory {

  private final DbClient dbClient;
  private final String agentId;

  public PgMemory(DbClient dbClient, String agentId) {
    this.dbClient = Objects.requireNonNull(dbClient, "dbClient");
    this.agentId = Objects.requireNonNull(agentId, "agentId");
  }

  // --- Core Blocks (deferred — no-ops) ---

  @Override
  public List<MemoryBlock> coreBlocks() {
    return List.of();
  }

  @Override
  public MemoryBlock block(String name) {
    return null;
  }

  @Override
  public void putBlock(MemoryBlock block) {
    // no-op — core blocks not yet supported in PgMemory
  }

  @Override
  public void updateBlock(String blockName, String key, Object value) {
    // no-op
  }

  @Override
  public void replaceBlock(String blockName, Map<String, Object> data) {
    // no-op
  }

  // --- Archival Memory (agent-scoped) ---

  @Override
  public void archive(String content, Map<String, Object> metadata) {
    try {
      var id = Ids.newId();
      var createdAt = Ids.now();
      dbClient
          .execute()
          .dml(
              ArchiveSql.INSERT,
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
        return ArchiveMapper.mapAll(dbClient.execute().query(ArchiveSql.FIND_ALL, agentId, limit));
      }
      return searchWithScim(
          query,
          ArchiveSql.SCIM_SELECT,
          "agent_id = :agentId",
          Map.of("agentId", agentId),
          ArchiveMapper::mapAll);
    } catch (Exception e) {
      throw new PgException("Failed to search archive", e);
    }
  }

  // --- Session History (session-scoped) ---

  @Override
  public List<Message> history(UUID sessionId) {
    try {
      return MessageMapper.mapAll(
          dbClient.execute().query(MessageSql.FIND_BY_SESSION, sessionId.toString()));
    } catch (Exception e) {
      throw new PgException("Failed to get history for session: " + sessionId, e);
    }
  }

  @Override
  public void addMessage(UUID sessionId, Message message) {
    try {
      var id = Ids.newId();
      var createdAt = Ids.now();
      dbClient
          .execute()
          .dml(
              MessageSql.INSERT,
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
  public void clearHistory(UUID sessionId) {
    try {
      dbClient.execute().dml(MessageSql.DELETE_BY_SESSION, sessionId.toString());
    } catch (Exception e) {
      throw new PgException("Failed to clear history for session: " + sessionId, e);
    }
  }

  @Override
  public List<Message> searchHistory(UUID sessionId, String query, int limit) {
    try {
      if (query == null || query.isBlank()) {
        return MessageMapper.mapAll(
            dbClient
                .execute()
                .query(MessageSql.FIND_BY_SESSION_LIMIT, sessionId.toString(), limit));
      }
      return searchWithScim(
          query,
          MessageSql.SCIM_SELECT,
          "session_id = CAST(:sessionId AS UUID)",
          Map.of("sessionId", sessionId.toString()),
          MessageMapper::mapAll);
    } catch (Exception e) {
      throw new PgException("Failed to search history for session: " + sessionId, e);
    }
  }

  // --- Shared SCIM query helper ---

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
