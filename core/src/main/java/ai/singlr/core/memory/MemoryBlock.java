/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A block of core memory that is always in context. Inspired by Letta's memory blocks.
 *
 * @param id unique identifier for this block
 * @param name the block name (e.g., "persona", "user_facts", "preferences")
 * @param description what this block is for
 * @param data the actual content as key-value pairs
 * @param maxSize maximum size in characters when serialized
 * @param createdAt when the block was created
 * @param updatedAt when the block was last updated
 */
public record MemoryBlock(
    String id,
    String name,
    String description,
    Map<String, Object> data,
    int maxSize,
    Instant createdAt,
    Instant updatedAt) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(MemoryBlock block) {
    return new Builder(block);
  }

  /** Create a copy with updated data and timestamp. */
  public MemoryBlock withData(Map<String, Object> newData) {
    return new MemoryBlock(
        id, name, description, Map.copyOf(newData), maxSize, createdAt, Instant.now());
  }

  /** Create a copy with a single key updated. */
  public MemoryBlock withValue(String key, Object value) {
    var newData = new HashMap<>(data);
    newData.put(key, value);
    return withData(newData);
  }

  /** Get a value from the data map. */
  @SuppressWarnings("unchecked")
  public <T> T value(String key) {
    return (T) data.get(key);
  }

  /** Get a value with a default. */
  @SuppressWarnings("unchecked")
  public <T> T value(String key, T defaultValue) {
    var val = data.get(key);
    return val != null ? (T) val : defaultValue;
  }

  /** Render the block as text for inclusion in prompts. */
  public String render() {
    var sb = new StringBuilder();
    sb.append("[").append(name).append("]\n");
    for (var entry : data.entrySet()) {
      sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
    }
    return sb.toString();
  }

  /** Builder for MemoryBlock. */
  public static class Builder {
    private String id;
    private String name;
    private String description;
    private Map<String, Object> data = new HashMap<>();
    private int maxSize = 2000;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Builder() {
      this.id = UUID.randomUUID().toString();
      this.createdAt = Instant.now();
      this.updatedAt = this.createdAt;
    }

    private Builder(MemoryBlock block) {
      this.id = block.id;
      this.name = block.name;
      this.description = block.description;
      this.data = new HashMap<>(block.data);
      this.maxSize = block.maxSize;
      this.createdAt = block.createdAt;
      this.updatedAt = block.updatedAt;
    }

    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public Builder withData(Map<String, Object> data) {
      this.data = data != null ? new HashMap<>(data) : new HashMap<>();
      return this;
    }

    public Builder withValue(String key, Object value) {
      this.data.put(key, value);
      return this;
    }

    public Builder withMaxSize(int maxSize) {
      this.maxSize = maxSize;
      return this;
    }

    public MemoryBlock build() {
      return new MemoryBlock(
          id, name, description, Map.copyOf(data), maxSize, createdAt, updatedAt);
    }
  }
}
