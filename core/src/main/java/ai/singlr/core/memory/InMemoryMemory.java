/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory implementation of Memory. Useful for testing and simple use cases. Thread-safe. */
public class InMemoryMemory implements Memory {

  private final Map<String, MemoryBlock> coreBlocks = new ConcurrentHashMap<>();
  private final List<ArchivalEntry> archival = new CopyOnWriteArrayList<>();
  private final List<Message> history = new CopyOnWriteArrayList<>();

  @Override
  public List<MemoryBlock> coreBlocks() {
    return List.copyOf(coreBlocks.values());
  }

  @Override
  public MemoryBlock block(String name) {
    return coreBlocks.get(name);
  }

  @Override
  public void putBlock(MemoryBlock block) {
    coreBlocks.put(block.name(), block);
  }

  @Override
  public void updateBlock(String blockName, String key, Object value) {
    coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withValue(key, value));
  }

  @Override
  public void replaceBlock(String blockName, Map<String, Object> data) {
    coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withData(data));
  }

  @Override
  public void archive(String content, Map<String, Object> metadata) {
    archival.add(ArchivalEntry.of(content, metadata));
  }

  @Override
  public List<ArchivalEntry> searchArchive(String query, int limit) {
    if (query == null || query.isBlank()) {
      return archival.stream().limit(limit).toList();
    }

    var queryLower = query.toLowerCase(Locale.ROOT);
    return archival.stream()
        .filter(e -> e.content().toLowerCase(Locale.ROOT).contains(queryLower))
        .limit(limit)
        .toList();
  }

  @Override
  public List<Message> history() {
    return List.copyOf(history);
  }

  @Override
  public void addMessage(Message message) {
    history.add(message);
  }

  @Override
  public void clearHistory() {
    history.clear();
  }

  @Override
  public List<Message> searchHistory(String query, int limit) {
    if (query == null || query.isBlank()) {
      return history.stream().limit(limit).toList();
    }

    var queryLower = query.toLowerCase(Locale.ROOT);
    return history.stream()
        .filter(
            m -> m.content() != null && m.content().toLowerCase(Locale.ROOT).contains(queryLower))
        .limit(limit)
        .toList();
  }

  /** Create a new InMemoryMemory with default blocks. */
  public static InMemoryMemory withDefaults() {
    var memory = new InMemoryMemory();
    memory.putBlock(
        MemoryBlock.newBuilder()
            .withName("persona")
            .withDescription("The agent's personality and behavior")
            .build());
    memory.putBlock(
        MemoryBlock.newBuilder()
            .withName("user")
            .withDescription("Information about the user")
            .build());
    return memory;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for InMemoryMemory. */
  public static class Builder {
    private final List<MemoryBlock> blocks = new ArrayList<>();

    private Builder() {}

    public Builder withBlock(MemoryBlock block) {
      blocks.add(block);
      return this;
    }

    public Builder withBlock(String name, String description) {
      blocks.add(MemoryBlock.newBuilder().withName(name).withDescription(description).build());
      return this;
    }

    public InMemoryMemory build() {
      var memory = new InMemoryMemory();
      for (var block : blocks) {
        memory.putBlock(block);
      }
      return memory;
    }
  }
}
