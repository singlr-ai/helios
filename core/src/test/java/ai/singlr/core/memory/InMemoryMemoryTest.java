/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryMemoryTest {

  private InMemoryMemory memory;

  @BeforeEach
  void setUp() {
    memory =
        InMemoryMemory.newBuilder()
            .withBlock("persona", "Agent personality")
            .withBlock("user", "User information")
            .build();
  }

  @Test
  void coreBlocks() {
    var blocks = memory.coreBlocks();

    assertEquals(2, blocks.size());
    assertNotNull(memory.block("persona"));
    assertNotNull(memory.block("user"));
  }

  @Test
  void updateBlock() {
    memory.updateBlock("user", "name", "Alice");

    var block = memory.block("user");
    assertEquals("Alice", block.value("name"));
  }

  @Test
  void replaceBlock() {
    memory.updateBlock("user", "name", "Alice");
    memory.replaceBlock("user", Map.of("name", "Bob", "age", 25));

    var block = memory.block("user");
    assertEquals("Bob", block.value("name"));
    assertEquals(25, block.<Integer>value("age"));
  }

  @Test
  void archiveAndSearch() {
    memory.archive("The weather is sunny today.");
    memory.archive("Meeting scheduled for 3 PM.");
    memory.archive("Don't forget to buy groceries.");

    var results = memory.searchArchive("weather", 10);
    assertEquals(1, results.size());
    assertTrue(results.getFirst().content().contains("weather"));
  }

  @Test
  void archiveWithMetadata() {
    memory.archive("Important note", Map.of("tags", "important"));

    var results = memory.searchArchive("note", 10);
    assertEquals(1, results.size());
    assertEquals("important", results.getFirst().metadata().get("tags"));
  }

  @Test
  void conversationHistory() {
    memory.addMessage(Message.user("Hello"));
    memory.addMessage(Message.assistant("Hi there!"));
    memory.addMessage(Message.user("How are you?"));

    var history = memory.history();
    assertEquals(3, history.size());
    assertEquals("Hello", history.get(0).content());
  }

  @Test
  void searchHistory() {
    memory.addMessage(Message.user("What's the weather?"));
    memory.addMessage(Message.assistant("It's sunny."));
    memory.addMessage(Message.user("Tell me a joke."));

    var results = memory.searchHistory("weather", 10);
    assertEquals(1, results.size());
    assertTrue(results.getFirst().content().contains("weather"));
  }

  @Test
  void clearHistory() {
    memory.addMessage(Message.user("Hello"));
    memory.clearHistory();

    assertTrue(memory.history().isEmpty());
  }

  @Test
  void renderCoreMemory() {
    memory.updateBlock("persona", "role", "assistant");
    memory.updateBlock("user", "name", "Alice");

    var rendered = memory.renderCoreMemory();

    assertTrue(rendered.contains("[persona]"));
    assertTrue(rendered.contains("role: assistant"));
    assertTrue(rendered.contains("[user]"));
    assertTrue(rendered.contains("name: Alice"));
  }

  @Test
  void withDefaults() {
    var defaultMemory = InMemoryMemory.withDefaults();

    assertNotNull(defaultMemory.block("persona"));
    assertNotNull(defaultMemory.block("user"));
  }

  @Test
  void tools() {
    var tools = memory.tools();

    assertFalse(tools.isEmpty());
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("core_memory_update")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("archival_memory_insert")));
  }

  @Test
  void searchArchiveWithBlankQuery() {
    memory.archive("Item 1");
    memory.archive("Item 2");
    memory.archive("Item 3");

    var results = memory.searchArchive("", 2);

    assertEquals(2, results.size());
  }

  @Test
  void searchArchiveWithNullQuery() {
    memory.archive("Item 1");
    memory.archive("Item 2");

    var results = memory.searchArchive(null, 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchHistoryWithBlankQuery() {
    memory.addMessage(Message.user("Hello"));
    memory.addMessage(Message.assistant("Hi"));

    var results = memory.searchHistory("", 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchHistoryWithNullQuery() {
    memory.addMessage(Message.user("Hello"));

    var results = memory.searchHistory(null, 10);

    assertEquals(1, results.size());
  }

  @Test
  void updateBlockNonExistent() {
    memory.updateBlock("nonexistent", "key", "value");

    assertNull(memory.block("nonexistent"));
  }

  @Test
  void replaceBlockNonExistent() {
    memory.replaceBlock("nonexistent", Map.of("key", "value"));

    assertNull(memory.block("nonexistent"));
  }

  @Test
  void withBlockObject() {
    var block =
        MemoryBlock.newBuilder()
            .withName("custom")
            .withDescription("Custom block")
            .withValue("key", "value")
            .build();

    var customMemory = InMemoryMemory.newBuilder().withBlock(block).build();

    assertNotNull(customMemory.block("custom"));
    assertEquals("value", customMemory.block("custom").value("key"));
  }

  @Test
  void putBlock() {
    var block = MemoryBlock.newBuilder().withName("newblock").withDescription("New block").build();

    memory.putBlock(block);

    assertNotNull(memory.block("newblock"));
    assertEquals(3, memory.coreBlocks().size());
  }

  @Test
  void searchHistorySkipsNullContent() {
    memory.addMessage(Message.assistant(null, null));
    memory.addMessage(Message.user("Has content"));

    var results = memory.searchHistory("content", 10);

    assertEquals(1, results.size());
  }
}
