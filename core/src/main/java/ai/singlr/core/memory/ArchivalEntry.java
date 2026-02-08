/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An entry in archival memory (long-term storage).
 *
 * @param id unique identifier
 * @param content the text content
 * @param metadata optional metadata (tags, source, etc.)
 * @param createdAt when the entry was created
 */
public record ArchivalEntry(
    String id, String content, Map<String, Object> metadata, Instant createdAt) {

  public static ArchivalEntry of(String content) {
    return new ArchivalEntry(UUID.randomUUID().toString(), content, Map.of(), Instant.now());
  }

  public static ArchivalEntry of(String content, Map<String, Object> metadata) {
    return new ArchivalEntry(
        UUID.randomUUID().toString(),
        content,
        metadata != null ? Map.copyOf(metadata) : Map.of(),
        Instant.now());
  }
}
