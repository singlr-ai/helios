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
import ai.singlr.core.model.Message;
import java.util.List;
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

  @Test
  void boundToReturnsAllTools() {
    var tools = MemoryTools.boundTo(memory, sessionId);

    assertEquals(6, tools.size());
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("core_memory_update")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("core_memory_replace")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("core_memory_read")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("archival_memory_insert")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("archival_memory_search")));
    assertTrue(tools.stream().anyMatch(t -> t.name().equals("conversation_search")));
  }

  @Test
  void coreMemoryUpdateSuccess() {
    var tool = MemoryTools.coreMemoryUpdate(memory);

    var result = tool.execute(Map.of("block", "user", "key", "name", "value", "Alice"));

    assertTrue(result.success());
    assertEquals("Updated user.name", result.output());
    assertEquals("Alice", memory.block("user").value("name"));
  }

  @Test
  void coreMemoryUpdateBlockNotFound() {
    var tool = MemoryTools.coreMemoryUpdate(memory);

    var result = tool.execute(Map.of("block", "nonexistent", "key", "name", "value", "Alice"));

    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void coreMemoryReplaceSuccess() {
    var tool = MemoryTools.coreMemoryReplace(memory);

    var result = tool.execute(Map.of("block", "user", "content", Map.of("name", "Bob", "age", 30)));

    assertTrue(result.success());
    assertTrue(result.output().contains("Replaced"));
    assertEquals("Bob", memory.block("user").value("name"));
    assertEquals(30, memory.block("user").<Integer>value("age"));
  }

  @Test
  void coreMemoryReplaceBlockNotFound() {
    var tool = MemoryTools.coreMemoryReplace(memory);

    var result = tool.execute(Map.of("block", "nonexistent", "content", Map.of("key", "value")));

    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void coreMemoryReplaceContentNotMap() {
    var tool = MemoryTools.coreMemoryReplace(memory);

    var result = tool.execute(Map.of("block", "user", "content", "not a map"));

    assertFalse(result.success());
    assertTrue(result.output().contains("must be an object"));
  }

  @Test
  void coreMemoryReadSuccess() {
    memory.updateBlock("user", "name", "Alice");
    var tool = MemoryTools.coreMemoryRead(memory);

    var result = tool.execute(Map.of("block", "user"));

    assertTrue(result.success());
    assertTrue(result.output().contains("[user]"));
    assertTrue(result.output().contains("name: Alice"));
    assertNotNull(result.data());
  }

  @Test
  void coreMemoryReadBlockNotFound() {
    var tool = MemoryTools.coreMemoryRead(memory);

    var result = tool.execute(Map.of("block", "nonexistent"));

    assertFalse(result.success());
    assertTrue(result.output().contains("not found"));
  }

  @Test
  void archivalInsertWithoutTags() {
    var tool = MemoryTools.archivalInsert(memory);

    var result = tool.execute(Map.of("content", "Important information"));

    assertTrue(result.success());
    assertEquals("Stored in archival memory", result.output());

    var archived = memory.searchArchive("Important", 10);
    assertEquals(1, archived.size());
  }

  @Test
  void archivalInsertWithTags() {
    var tool = MemoryTools.archivalInsert(memory);

    var result =
        tool.execute(Map.of("content", "Tagged content", "tags", List.of("important", "todo")));

    assertTrue(result.success());
    var archived = memory.searchArchive("Tagged", 10);
    assertEquals(1, archived.size());
    assertNotNull(archived.getFirst().metadata().get("tags"));
  }

  @Test
  void archivalSearchWithResults() {
    memory.archive("Weather is sunny");
    memory.archive("Meeting at noon");
    var tool = MemoryTools.archivalSearch(memory);

    var result = tool.execute(Map.of("query", "sunny"));

    assertTrue(result.success());
    assertTrue(result.output().contains("sunny"));
    assertNotNull(result.data());
  }

  @Test
  void archivalSearchNoResults() {
    var tool = MemoryTools.archivalSearch(memory);

    var result = tool.execute(Map.of("query", "nonexistent"));

    assertTrue(result.success());
    assertEquals("No results found", result.output());
  }

  @Test
  void archivalSearchWithLimit() {
    memory.archive("Item 1");
    memory.archive("Item 2");
    memory.archive("Item 3");
    var tool = MemoryTools.archivalSearch(memory);

    var result = tool.execute(Map.of("query", "Item", "limit", 2));

    assertTrue(result.success());
    @SuppressWarnings("unchecked")
    var results = (List<ArchivalEntry>) result.data();
    assertEquals(2, results.size());
  }

  @Test
  void archivalSearchDefaultLimit() {
    for (int i = 0; i < 10; i++) {
      memory.archive("Item " + i);
    }
    var tool = MemoryTools.archivalSearch(memory);

    var result = tool.execute(Map.of("query", "Item"));

    assertTrue(result.success());
    @SuppressWarnings("unchecked")
    var results = (List<ArchivalEntry>) result.data();
    assertEquals(5, results.size());
  }

  @Test
  void conversationSearchWithResults() {
    memory.addMessage(sessionId, Message.user("What's the weather?"));
    memory.addMessage(sessionId, Message.assistant("It's sunny today."));
    var tool = MemoryTools.conversationSearch(memory, sessionId);

    var result = tool.execute(Map.of("query", "weather"));

    assertTrue(result.success());
    assertTrue(result.output().contains("weather"));
    assertTrue(result.output().contains("[USER]"));
    assertNotNull(result.data());
  }

  @Test
  void conversationSearchNoResults() {
    var tool = MemoryTools.conversationSearch(memory, sessionId);

    var result = tool.execute(Map.of("query", "nonexistent"));

    assertTrue(result.success());
    assertEquals("No matching messages found", result.output());
  }

  @Test
  void conversationSearchWithLimit() {
    for (int i = 0; i < 15; i++) {
      memory.addMessage(sessionId, Message.user("Message " + i));
    }
    var tool = MemoryTools.conversationSearch(memory, sessionId);

    var result = tool.execute(Map.of("query", "Message", "limit", 5));

    assertTrue(result.success());
    @SuppressWarnings("unchecked")
    var results = (List<Message>) result.data();
    assertEquals(5, results.size());
  }

  @Test
  void conversationSearchDefaultLimit() {
    for (int i = 0; i < 15; i++) {
      memory.addMessage(sessionId, Message.user("Message " + i));
    }
    var tool = MemoryTools.conversationSearch(memory, sessionId);

    var result = tool.execute(Map.of("query", "Message"));

    assertTrue(result.success());
    @SuppressWarnings("unchecked")
    var results = (List<Message>) result.data();
    assertEquals(10, results.size());
  }
}
