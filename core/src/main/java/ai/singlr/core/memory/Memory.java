/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Map;

/**
 * Memory interface following Letta's two-tier model.
 *
 * <ul>
 *   <li>Core memory: always in context (memory blocks)
 *   <li>Archival memory: retrieved on demand (long-term storage)
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

  /** Get conversation history. */
  List<Message> history();

  /** Add a message to history. */
  void addMessage(Message message);

  /** Clear conversation history. */
  void clearHistory();

  /** Search conversation history. */
  List<Message> searchHistory(String query, int limit);

  /** Get tools bound to this memory instance. These tools allow the agent to self-edit memory. */
  default List<Tool> tools() {
    return MemoryTools.boundTo(this);
  }
}
