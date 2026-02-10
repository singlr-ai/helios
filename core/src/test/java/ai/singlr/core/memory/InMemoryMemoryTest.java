/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.model.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryMemoryTest {

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
    memory.addMessage(sessionId, Message.user("Hello"));
    memory.addMessage(sessionId, Message.assistant("Hi there!"));
    memory.addMessage(sessionId, Message.user("How are you?"));

    var history = memory.history(sessionId);
    assertEquals(3, history.size());
    assertEquals("Hello", history.get(0).content());
  }

  @Test
  void searchHistory() {
    memory.addMessage(sessionId, Message.user("What's the weather?"));
    memory.addMessage(sessionId, Message.assistant("It's sunny."));
    memory.addMessage(sessionId, Message.user("Tell me a joke."));

    var results = memory.searchHistory(sessionId, "weather", 10);
    assertEquals(1, results.size());
    assertTrue(results.getFirst().content().contains("weather"));
  }

  @Test
  void clearHistory() {
    memory.addMessage(sessionId, Message.user("Hello"));
    memory.clearHistory(sessionId);

    assertTrue(memory.history(sessionId).isEmpty());
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
    memory.addMessage(sessionId, Message.user("Hello"));
    memory.addMessage(sessionId, Message.assistant("Hi"));

    var results = memory.searchHistory(sessionId, "", 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchHistoryWithNullQuery() {
    memory.addMessage(sessionId, Message.user("Hello"));

    var results = memory.searchHistory(sessionId, null, 10);

    assertEquals(1, results.size());
  }

  @Test
  void updateBlockNonExistentThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> memory.updateBlock("nonexistent", "key", "value"));
  }

  @Test
  void replaceBlockNonExistentThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> memory.replaceBlock("nonexistent", Map.of("key", "value")));
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
    memory.addMessage(sessionId, Message.assistant(null, null));
    memory.addMessage(sessionId, Message.user("Has content"));

    var results = memory.searchHistory(sessionId, "content", 10);

    assertEquals(1, results.size());
  }

  @Test
  void sessionsAreIsolated() {
    var session1 = Ids.newId();
    var session2 = Ids.newId();

    memory.addMessage(session1, Message.user("Session 1 message"));
    memory.addMessage(session2, Message.user("Session 2 message"));

    assertEquals(1, memory.history(session1).size());
    assertEquals(1, memory.history(session2).size());
    assertEquals("Session 1 message", memory.history(session1).getFirst().content());
    assertEquals("Session 2 message", memory.history(session2).getFirst().content());
  }

  @Test
  void historyForUnknownSessionIsEmpty() {
    assertTrue(memory.history(Ids.newId()).isEmpty());
  }

  @Test
  void searchHistoryForUnknownSessionIsEmpty() {
    assertTrue(memory.searchHistory(Ids.newId(), "anything", 10).isEmpty());
  }
}
