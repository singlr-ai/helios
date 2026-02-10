/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Memory interface following Letta's two-tier model.
 *
 * <ul>
 *   <li>Core memory: always in context (memory blocks) — agent-level
 *   <li>Archival memory: retrieved on demand (long-term storage)
 *   <li>Conversation history: session-scoped, keyed by session UUID
 * </ul>
 */
public interface Memory {

  /** Get all core memory blocks. */
  List<MemoryBlock> coreBlocks();

  /** Get a specific memory block by name. */
  MemoryBlock block(String name);

  /** Create or update a memory block. */
  void putBlock(MemoryBlock block);

  /** Update a value in a memory block. */
  void updateBlock(String blockName, String key, Object value);

  /** Replace all data in a memory block. */
  void replaceBlock(String blockName, Map<String, Object> data);

  /** Render all core memory blocks as text for prompts. */
  default String renderCoreMemory() {
    var sb = new StringBuilder();
    for (var block : coreBlocks()) {
      sb.append(block.render()).append("\n");
    }
    return sb.toString();
  }

  /** Store content in archival memory. */
  void archive(String content, Map<String, Object> metadata);

  /** Store content in archival memory without metadata. */
  default void archive(String content) {
    archive(content, Map.of());
  }

  /**
   * Search archival memory. In-memory implementation does simple text matching. Vector DB
   * implementations do semantic search.
   */
  List<ArchivalEntry> searchArchive(String query, int limit);

  /** Get conversation history for a session. */
  List<Message> history(String userId, UUID sessionId);

  /** Add a message to a session's history. */
  void addMessage(String userId, UUID sessionId, Message message);

  /** Clear conversation history for a session. */
  void clearHistory(String userId, UUID sessionId);

  /** Search conversation history for a session. */
  List<Message> searchHistory(String userId, UUID sessionId, String query, int limit);

  /** Register a session for a user. Idempotent — updates last-active time on re-registration. */
  void registerSession(String userId, UUID sessionId);

  /** Find the most recently active session for a user. */
  Optional<UUID> latestSession(String userId);

  /** Find all sessions for a user, most recently active first. */
  List<UUID> sessions(String userId);
}
