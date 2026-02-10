/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory implementation of Memory. Useful for testing and simple use cases. Thread-safe. */
public class InMemoryMemory implements Memory {

  private record SessionEntry(String userId, long sequence) {}

  private final AtomicLong sequenceCounter = new AtomicLong();
  private final Map<String, MemoryBlock> coreBlocks = new ConcurrentHashMap<>();
  private final List<ArchivalEntry> archival = new CopyOnWriteArrayList<>();
  private final Map<UUID, List<Message>> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, SessionEntry> sessionRegistry = new ConcurrentHashMap<>();

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
    var updated =
        coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withValue(key, value));
    if (updated == null) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
  }

  @Override
  public void replaceBlock(String blockName, Map<String, Object> data) {
    var updated = coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withData(data));
    if (updated == null) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
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
  public List<Message> history(String userId, UUID sessionId) {
    var messages = sessions.get(sessionId);
    return messages != null ? List.copyOf(messages) : List.of();
  }

  @Override
  public void addMessage(String userId, UUID sessionId, Message message) {
    sessions.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(message);
  }

  @Override
  public void clearHistory(String userId, UUID sessionId) {
    sessions.remove(sessionId);
  }

  @Override
  public List<Message> searchHistory(String userId, UUID sessionId, String query, int limit) {
    var messages = sessions.get(sessionId);
    if (messages == null) {
      return List.of();
    }

    if (query == null || query.isBlank()) {
      return messages.stream().limit(limit).toList();
    }

    var queryLower = query.toLowerCase(Locale.ROOT);
    return messages.stream()
        .filter(
            m -> m.content() != null && m.content().toLowerCase(Locale.ROOT).contains(queryLower))
        .limit(limit)
        .toList();
  }

  @Override
  public void registerSession(String userId, UUID sessionId) {
    sessionRegistry.put(sessionId, new SessionEntry(userId, sequenceCounter.incrementAndGet()));
  }

  @Override
  public Optional<UUID> latestSession(String userId) {
    return sessionRegistry.entrySet().stream()
        .filter(e -> userId.equals(e.getValue().userId()))
        .max(Comparator.comparingLong(e -> e.getValue().sequence()))
        .map(Map.Entry::getKey);
  }

  @Override
  public List<UUID> sessions(String userId) {
    return sessionRegistry.entrySet().stream()
        .filter(e -> userId.equals(e.getValue().userId()))
        .sorted(
            Comparator.<Map.Entry<UUID, SessionEntry>>comparingLong(e -> e.getValue().sequence())
                .reversed())
        .map(Map.Entry::getKey)
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
