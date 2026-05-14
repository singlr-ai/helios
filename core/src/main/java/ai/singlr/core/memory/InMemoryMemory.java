/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.common.Ids;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Message;
import java.time.Instant;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/** In-memory implementation of Memory. Useful for testing and simple use cases. Thread-safe. */
public class InMemoryMemory implements Memory {

  private static final Logger LOG = Logger.getLogger(InMemoryMemory.class.getName());

  private record SessionKey(String userId, UUID sessionId) {}

  private record SessionEntry(String userId, long sequence, java.time.Instant lastActiveAt) {}

  private final AtomicLong sequenceCounter = new AtomicLong();
  private final Map<String, MemoryBlock> coreBlocks = new ConcurrentHashMap<>();
  private final List<ArchivalEntry> archival = new CopyOnWriteArrayList<>();
  private final Map<SessionKey, List<Message>> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, SessionEntry> sessionRegistry = new ConcurrentHashMap<>();
  private final List<EventSink> eventSinks = new CopyOnWriteArrayList<>();
  private final UUID memoryRunId = Ids.newId();

  @Override
  public List<MemoryBlock> coreBlocks() {
    return coreBlocks.values().stream().sorted(Comparator.comparing(MemoryBlock::name)).toList();
  }

  @Override
  public Optional<MemoryBlock> block(String name) {
    return Optional.ofNullable(coreBlocks.get(name));
  }

  @Override
  public void putBlock(MemoryBlock block) {
    coreBlocks.put(block.name(), block);
    fireWrite(block.name(), "put");
  }

  @Override
  public void updateBlock(String blockName, String key, Object value) {
    var updated =
        coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withValue(key, value));
    if (updated == null) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
    fireWrite(blockName, "update");
  }

  @Override
  public void replaceBlock(String blockName, Map<String, Object> data) {
    var updated = coreBlocks.computeIfPresent(blockName, (k, existing) -> existing.withData(data));
    if (updated == null) {
      throw new IllegalArgumentException("Memory block not found: " + blockName);
    }
    fireWrite(blockName, "replace");
  }

  @Override
  public boolean removeBlock(String blockName) {
    var removed = coreBlocks.remove(blockName);
    if (removed == null) {
      return false;
    }
    fireWrite(blockName, "remove");
    return true;
  }

  @Override
  public void archive(String content, Map<String, Object> metadata) {
    archival.add(ArchivalEntry.of(content, metadata));
    fireWrite("__archive__", "archive");
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
    var messages = sessions.get(new SessionKey(userId, sessionId));
    return messages != null ? List.copyOf(messages) : List.of();
  }

  @Override
  public void addMessage(String userId, UUID sessionId, Message message) {
    sessions
        .computeIfAbsent(new SessionKey(userId, sessionId), k -> new CopyOnWriteArrayList<>())
        .add(message);
  }

  @Override
  public void clearHistory(String userId, UUID sessionId) {
    sessions.remove(new SessionKey(userId, sessionId));
  }

  @Override
  public List<Message> searchHistory(String userId, UUID sessionId, String query, int limit) {
    var messages = sessions.get(new SessionKey(userId, sessionId));
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
    sessionRegistry.put(
        sessionId,
        new SessionEntry(userId, sequenceCounter.incrementAndGet(), java.time.Instant.now()));
  }

  @Override
  public int purgeSessionsOlderThan(java.time.Duration olderThan) {
    if (olderThan == null) {
      throw new IllegalArgumentException("olderThan must not be null");
    }
    if (olderThan.isNegative()) {
      throw new IllegalArgumentException("olderThan must be non-negative");
    }
    var cutoff = java.time.Instant.now().minus(olderThan);
    var removed = 0;
    var it = sessionRegistry.entrySet().iterator();
    while (it.hasNext()) {
      var entry = it.next();
      if (entry.getValue().lastActiveAt().isBefore(cutoff)) {
        var sessionId = entry.getKey();
        var userId = entry.getValue().userId();
        it.remove();
        // Cascade: remove the associated messages for every user that touched this session id.
        sessions.keySet().removeIf(k -> k.sessionId().equals(sessionId));
        removed++;
        // Silence unused warning while keeping userId available for future per-user telemetry.
        if (userId == null) {
          LOG.log(Level.FINEST, "purged orphan session with null user id");
        }
      }
    }
    return removed;
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

  @Override
  public void addEventSink(EventSink sink) {
    if (sink == null) {
      throw new IllegalArgumentException("sink must not be null");
    }
    // Idempotent — protects against double-subscription when multiple Agent instances share a
    // single Memory and each tries to attach the same sink at construction time.
    if (!eventSinks.contains(sink)) {
      eventSinks.add(sink);
    }
  }

  @Override
  public void removeEventSink(EventSink sink) {
    eventSinks.remove(sink);
  }

  /**
   * Emit a {@link HeliosEvent.MemoryWritten} to every registered sink. Each handler runs on the
   * mutator's thread; exceptions are caught and logged at WARNING so a broken sink does not corrupt
   * the store or abort the caller.
   *
   * <p>The event's {@code runId} is the {@link #memoryRunId} — a per-Memory-instance UUID, not a
   * per-agent-run id. This intentional decoupling prevents the shared-memory leak problem (see
   * CLAUDE.md): subscribers that want both agent-loop events and memory-write events register on
   * both {@code AgentConfig.withEventSink} and {@code Memory.addEventSink} and correlate via
   * timestamps if needed.
   */
  private void fireWrite(String blockName, String operation) {
    if (eventSinks.isEmpty()) {
      return;
    }
    var event =
        new HeliosEvent.MemoryWritten(
            Instant.now(), memoryRunId, Optional.empty(), blockName, operation);
    for (var sink : eventSinks) {
      try {
        sink.onEvent(event);
      } catch (RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "EventSink.onEvent threw on MemoryWritten — ignoring; sink=" + sink.getClass(),
            e);
      }
    }
  }

  /**
   * Create a new InMemoryMemory with the three canonical blocks pre-installed: {@link
   * MemoryBlocks#IDENTITY}, {@link MemoryBlocks#USER_PROFILE}, {@link MemoryBlocks#WORKING_MEMORY}.
   */
  public static InMemoryMemory withDefaults() {
    var memory = new InMemoryMemory();
    memory.putBlock(MemoryBlocks.identity().build());
    memory.putBlock(MemoryBlocks.userProfile().build());
    memory.putBlock(MemoryBlocks.workingMemory().build());
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

    public Builder withBlock(String name, String description, int maxSize) {
      blocks.add(
          MemoryBlock.newBuilder()
              .withName(name)
              .withDescription(description)
              .withMaxSize(maxSize)
              .build());
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
