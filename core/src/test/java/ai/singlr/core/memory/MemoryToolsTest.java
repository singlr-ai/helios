/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryToolsTest {

  private InMemoryMemory memory;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    memory =
        InMemoryMemory.newBuilder()
            .withBlock("persona", "Agent personality")
            .withBlock("user", "User information")
            .build();
    sessionId = Ids.newId();
  }

  // --- boundTo ----------------------------------------------------------------------------------

  @Test
  void boundToReturnsTwoTools() {
    var tools = MemoryTools.boundTo(memory, null, sessionId);

    assertEquals(2, tools.size());
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("memory_update")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("memory_read")));
  }

  // --- memory_update ----------------------------------------------------------------------------

  @Test
  void memoryUpdateSuccess() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", "user", "key", "name", "value", "Alice"));

    assertTrue(result.success());
    assertEquals("Updated user.name", result.output());
    assertEquals("Alice", memory.block("user").orElseThrow().value("name"));
  }

  @Test
  void memoryUpdateBlockNotFound() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", "nonexistent", "key", "name", "value", "Alice"));

    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void memoryUpdateMissingBlockParam() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("key", "name", "value", "Alice"));

    assertFalse(result.success());
    assertTrue(result.output().contains("block"));
  }

  @Test
  void memoryUpdateMissingKeyParam() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", "user", "value", "Alice"));

    assertFalse(result.success());
    assertTrue(result.output().contains("key"));
  }

  @Test
  void memoryUpdateMissingValueParam() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", "user", "key", "name"));

    assertFalse(result.success());
    assertTrue(result.output().contains("value"));
  }

  @Test
  void memoryUpdateNonStringBlockParam() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", 42, "key", "name", "value", "Alice"));

    assertFalse(result.success());
    assertTrue(result.output().contains("block"));
  }

  @Test
  void memoryUpdateNonStringValueParam() {
    var tool = MemoryTools.memoryUpdate(memory);

    var result = tool.execute(Map.of("block", "user", "key", "name", "value", 42));

    assertFalse(result.success());
    assertTrue(result.output().contains("value"));
  }

  @Test
  void memoryUpdateExceedsMaxSize() {
    var smallMemory = InMemoryMemory.newBuilder().withBlock("tiny", "Small block", 50).build();
    var tool = MemoryTools.memoryUpdate(smallMemory);

    var result = tool.execute(Map.of("block", "tiny", "key", "data", "value", "X".repeat(200)));

    assertFalse(result.success());
    assertTrue(result.output().contains("exceed block size limit"));
    assertTrue(result.output().contains("50"));
  }

  @Test
  void memoryUpdateWithinMaxSize() {
    var smallMemory = InMemoryMemory.newBuilder().withBlock("tiny", "Small block", 500).build();
    var tool = MemoryTools.memoryUpdate(smallMemory);

    var result = tool.execute(Map.of("block", "tiny", "key", "name", "value", "Alice"));

    assertTrue(result.success());
  }

  // --- memory_read ------------------------------------------------------------------------------

  @Test
  void memoryReadSuccess() {
    memory.updateBlock("user", "name", "Alice");
    var tool = MemoryTools.memoryRead(memory);

    var result = tool.execute(Map.of("block", "user"));

    assertTrue(result.success());
    assertTrue(result.output().contains("<core-memory-block name=\"user\">"));
    assertTrue(result.output().contains("name: Alice"));
    assertNotNull(result.data());
  }

  @Test
  void memoryReadBlockNotFound() {
    var tool = MemoryTools.memoryRead(memory);

    var result = tool.execute(Map.of("block", "nonexistent"));

    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void memoryReadMissingBlockParam() {
    var tool = MemoryTools.memoryRead(memory);

    var result = tool.execute(Map.of());

    assertFalse(result.success());
    assertTrue(result.output().contains("block"));
  }

  @Test
  void memoryReadNonStringBlockParam() {
    var tool = MemoryTools.memoryRead(memory);

    var result = tool.execute(Map.of("block", 42));

    assertFalse(result.success());
    assertTrue(result.output().contains("block"));
  }
}
