/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchivalEntryTest {

  @Test
  void ofWithContentOnly() {
    var entry = ArchivalEntry.of("Important information");

    assertNotNull(entry.id());
    assertEquals("Important information", entry.content());
    assertTrue(entry.metadata().isEmpty());
    assertNotNull(entry.createdAt());
  }

  @Test
  void ofWithMetadata() {
    var metadata = Map.<String, Object>of("source", "user", "priority", "high");
    var entry = ArchivalEntry.of("Tagged content", metadata);

    assertNotNull(entry.id());
    assertEquals("Tagged content", entry.content());
    assertEquals("user", entry.metadata().get("source"));
    assertEquals("high", entry.metadata().get("priority"));
    assertNotNull(entry.createdAt());
  }

  @Test
  void ofWithNullMetadata() {
    var entry = ArchivalEntry.of("Content", null);

    assertTrue(entry.metadata().isEmpty());
  }

  @Test
  void metadataIsImmutable() {
    var mutableMetadata = new java.util.HashMap<String, Object>();
    mutableMetadata.put("key", "value");

    var entry = ArchivalEntry.of("Content", mutableMetadata);

    mutableMetadata.put("key2", "value2");

    assertEquals(1, entry.metadata().size());
  }

  @Test
  void uniqueIds() {
    var entry1 = ArchivalEntry.of("Content 1");
    var entry2 = ArchivalEntry.of("Content 2");

    assertNotNull(entry1.id());
    assertNotNull(entry2.id());
    assertTrue(!entry1.id().equals(entry2.id()));
  }
}
