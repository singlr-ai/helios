/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryBlockTest {

  @Test
  void buildBlock() {
    var block =
        MemoryBlock.newBuilder()
            .withName("user")
            .withDescription("Information about the user")
            .withValue("name", "Alice")
            .withValue("age", 30)
            .build();

    assertEquals("user", block.name());
    assertEquals("Information about the user", block.description());
    assertEquals("Alice", block.value("name"));
    assertEquals(30, block.<Integer>value("age"));
    assertNotNull(block.createdAt());
  }

  @Test
  void withValueCreatesNewBlock() {
    var original = MemoryBlock.newBuilder().withName("test").withValue("key", "value1").build();

    var modified = original.withValue("key", "value2");

    assertEquals("value1", original.value("key"));
    assertEquals("value2", modified.value("key"));
    assertEquals(original.name(), modified.name());
    assertNotEquals(original.updatedAt(), modified.updatedAt());
  }

  @Test
  void withDataCreatesNewBlock() {
    var original =
        MemoryBlock.newBuilder().withName("test").withData(Map.of("a", 1, "b", 2)).build();

    var modified = original.withData(Map.of("c", 3));

    assertEquals(1, original.<Integer>value("a"));
    assertNull(modified.value("a"));
    assertEquals(3, modified.<Integer>value("c"));
  }

  @Test
  void valueWithDefault() {
    var block = MemoryBlock.newBuilder().withName("test").withValue("exists", "yes").build();

    assertEquals("yes", block.value("exists", "no"));
    assertEquals("no", block.value("missing", "no"));
  }

  @Test
  void render() {
    var block =
        MemoryBlock.newBuilder()
            .withName("persona")
            .withValue("role", "assistant")
            .withValue("style", "helpful")
            .build();

    var rendered = block.render();

    assertTrue(rendered.startsWith("<core-memory-block name=\"persona\">\n"));
    assertTrue(rendered.endsWith("</core-memory-block>\n"));
    assertTrue(rendered.contains("role: assistant"));
    assertTrue(rendered.contains("style: helpful"));
  }

  @Test
  void renderWithDescription() {
    var block =
        MemoryBlock.newBuilder()
            .withName("user")
            .withDescription("Information about the user")
            .withValue("name", "Alice")
            .build();

    var rendered = block.render();

    assertTrue(rendered.startsWith("<core-memory-block name=\"user\">\n"));
    assertTrue(rendered.contains("[description: Information about the user]"));
    assertTrue(rendered.contains("name: Alice"));
  }

  @Test
  void renderWithEmptyDescription() {
    var block =
        MemoryBlock.newBuilder()
            .withName("test")
            .withDescription("")
            .withValue("key", "val")
            .build();

    var rendered = block.render();

    assertTrue(rendered.startsWith("<core-memory-block name=\"test\">\n"));
    assertFalse(rendered.contains("[description:"));
  }

  @Test
  void maxSize() {
    var block = MemoryBlock.newBuilder().withName("test").withMaxSize(5000).build();

    assertEquals(5000, block.maxSize());
  }

  @Test
  void defaultMaxSize() {
    var block = MemoryBlock.newBuilder().withName("test").build();

    assertEquals(2000, block.maxSize());
  }

  @Test
  void copyBuilder() {
    var original = MemoryBlock.newBuilder().withName("original").withValue("key", "value").build();

    var copy = MemoryBlock.newBuilder(original).withName("copy").build();

    assertEquals("original", original.name());
    assertEquals("copy", copy.name());
    assertEquals("value", copy.value("key"));
  }

  @Test
  void withDataNull() {
    var block = MemoryBlock.newBuilder().withName("test").withData(null).build();

    assertTrue(block.data().isEmpty());
  }

  @Test
  void timestampsPreservedAcrossWithData() {
    var original = MemoryBlock.newBuilder().withName("test").withValue("key", "v").build();

    var modified = original.withData(Map.of("key", "v2"));

    assertEquals(original.createdAt(), modified.createdAt());
    assertNotEquals(original.updatedAt(), modified.updatedAt());
  }
}
