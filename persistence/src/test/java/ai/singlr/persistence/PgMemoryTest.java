/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ToolCall;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PgMemoryTest {

  private PgMemory memory;

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateMemory();
    memory = new PgMemory(PgTestSupport.pgConfig("test-agent"));
  }

  // --- Archival ---

  @Test
  void archiveAndSearchByContent() {
    memory.archive("The quick brown fox jumps over the lazy dog");
    memory.archive("Java is a programming language");

    var results = memory.searchArchive("content co \"fox\"", 10);

    assertEquals(1, results.size());
    assertEquals("The quick brown fox jumps over the lazy dog", results.getFirst().content());
  }

  @Test
  void searchArchiveEmptyQueryReturnsAll() {
    memory.archive("entry one");
    memory.archive("entry two");

    var results = memory.searchArchive("", 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchArchiveNullQueryReturnsAll() {
    memory.archive("entry one");

    var results = memory.searchArchive(null, 10);

    assertEquals(1, results.size());
  }

  @Test
  void searchArchiveNoMatch() {
    memory.archive("hello world");

    var results = memory.searchArchive("content co \"nonexistent\"", 10);

    assertTrue(results.isEmpty());
  }

  @Test
  void archiveWithMetadata() {
    memory.archive("tagged content", Map.of("source", "test", "tag", "important"));

    var results = memory.searchArchive("content co \"tagged\"", 10);

    assertEquals(1, results.size());
    var entry = results.getFirst();
    assertNotNull(entry.id());
    assertNotNull(entry.createdAt());
    assertEquals("test", entry.metadata().get("source"));
    assertEquals("important", entry.metadata().get("tag"));
  }

  @Test
  void archiveIsolatedByAgentId() {
    var otherMemory = new PgMemory(PgTestSupport.pgConfig("other-agent"));
    memory.archive("agent-1 data");
    otherMemory.archive("agent-2 data");

    assertEquals(1, memory.searchArchive("", 10).size());
    assertEquals(1, otherMemory.searchArchive("", 10).size());
    assertEquals("agent-1 data", memory.searchArchive("", 10).getFirst().content());
  }

  // --- Session History ---

  @Test
  void addAndRetrieveHistory() {
    var sessionId = UUID.randomUUID();
    memory.addMessage("testuser", sessionId, Message.user("Hello"));
    memory.addMessage("testuser", sessionId, Message.assistant("Hi there"));

    var history = memory.history("testuser", sessionId);

    assertEquals(2, history.size());
    assertEquals("Hello", history.get(0).content());
    assertEquals("Hi there", history.get(1).content());
  }

  @Test
  void historyPreservesOrdering() {
    var sessionId = UUID.randomUUID();
    for (int i = 1; i <= 5; i++) {
      memory.addMessage("testuser", sessionId, Message.user("msg-" + i));
    }

    var history = memory.history("testuser", sessionId);

    assertEquals(5, history.size());
    for (int i = 0; i < 5; i++) {
      assertEquals("msg-" + (i + 1), history.get(i).content());
    }
  }

  @Test
  void historyForUnknownSessionReturnsEmpty() {
    var history = memory.history("testuser", UUID.randomUUID());
    assertTrue(history.isEmpty());
  }

  @Test
  void clearHistory() {
    var sessionId = UUID.randomUUID();
    memory.addMessage("testuser", sessionId, Message.user("Hello"));
    memory.addMessage("testuser", sessionId, Message.assistant("Hi"));

    memory.clearHistory("testuser", sessionId);

    assertTrue(memory.history("testuser", sessionId).isEmpty());
  }

  @Test
  void sessionIsolation() {
    var session1 = UUID.randomUUID();
    var session2 = UUID.randomUUID();
    memory.addMessage("testuser", session1, Message.user("Session 1"));
    memory.addMessage("testuser", session2, Message.user("Session 2"));

    var history1 = memory.history("testuser", session1);
    var history2 = memory.history("testuser", session2);

    assertEquals(1, history1.size());
    assertEquals("Session 1", history1.getFirst().content());
    assertEquals(1, history2.size());
    assertEquals("Session 2", history2.getFirst().content());
  }

  @Test
  void messageWithToolCallsRoundTrip() {
    var sessionId = UUID.randomUUID();
    var toolCall =
        ToolCall.newBuilder()
            .withId("call-1")
            .withName("get_weather")
            .withArguments(Map.of("location", "SF", "units", "fahrenheit"))
            .build();
    memory.addMessage("testuser", sessionId, Message.assistant("Let me check", List.of(toolCall)));
    memory.addMessage("testuser", sessionId, Message.tool("call-1", "get_weather", "72°F sunny"));

    var history = memory.history("testuser", sessionId);

    assertEquals(2, history.size());
    var assistantMsg = history.get(0);
    assertTrue(assistantMsg.hasToolCalls());
    var roundTripped = assistantMsg.toolCalls().getFirst();
    assertEquals("call-1", roundTripped.id());
    assertEquals("get_weather", roundTripped.name());
    assertEquals("SF", roundTripped.arguments().get("location"));
    assertEquals("fahrenheit", roundTripped.arguments().get("units"));
    var toolMsg = history.get(1);
    assertEquals("call-1", toolMsg.toolCallId());
    assertEquals("get_weather", toolMsg.toolName());
    assertEquals("72°F sunny", toolMsg.content());
  }

  // --- searchHistory with SCIM ---

  @Test
  void searchHistoryBlankQueryReturnsLimited() {
    var sessionId = UUID.randomUUID();
    for (int i = 0; i < 5; i++) {
      memory.addMessage("testuser", sessionId, Message.user("msg-" + i));
    }

    var results = memory.searchHistory("testuser", sessionId, "", 3);

    assertEquals(3, results.size());
  }

  @Test
  void searchHistoryByRole() {
    var sessionId = UUID.randomUUID();
    memory.addMessage("testuser", sessionId, Message.user("Hello"));
    memory.addMessage("testuser", sessionId, Message.assistant("Hi"));
    memory.addMessage("testuser", sessionId, Message.user("How are you?"));

    var results = memory.searchHistory("testuser", sessionId, "role eq \"USER\"", 10);

    assertEquals(2, results.size());
    assertEquals("Hello", results.get(0).content());
    assertEquals("How are you?", results.get(1).content());
  }

  @Test
  void searchHistoryByContentContains() {
    var sessionId = UUID.randomUUID();
    memory.addMessage("testuser", sessionId, Message.user("Hello world"));
    memory.addMessage("testuser", sessionId, Message.user("Goodbye world"));
    memory.addMessage("testuser", sessionId, Message.user("Hello again"));

    var results = memory.searchHistory("testuser", sessionId, "content co \"Hello\"", 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchHistoryCompoundFilter() {
    var sessionId = UUID.randomUUID();
    memory.addMessage("testuser", sessionId, Message.user("Hello"));
    memory.addMessage("testuser", sessionId, Message.assistant("Hi there"));
    memory.addMessage("testuser", sessionId, Message.user("Goodbye"));

    var results =
        memory.searchHistory(
            "testuser", sessionId, "role eq \"USER\" and content co \"Hello\"", 10);

    assertEquals(1, results.size());
    assertEquals("Hello", results.getFirst().content());
  }

  @Test
  void searchHistoryEmptySessionReturnsEmpty() {
    var results = memory.searchHistory("testuser", UUID.randomUUID(), "role eq \"USER\"", 10);
    assertTrue(results.isEmpty());
  }

  // --- Session Registry ---

  @Test
  void registerSessionAndFindLatest() {
    var session1 = UUID.randomUUID();
    var session2 = UUID.randomUUID();

    memory.registerSession("alice", session1);
    memory.registerSession("alice", session2);

    var latest = memory.latestSession("alice");
    assertTrue(latest.isPresent());
    assertEquals(session2, latest.get());
  }

  @Test
  void sessionsReturnsAllForUser() {
    var session1 = UUID.randomUUID();
    var session2 = UUID.randomUUID();

    memory.registerSession("bob", session1);
    memory.registerSession("bob", session2);

    var sessions = memory.sessions("bob");
    assertEquals(2, sessions.size());
    assertEquals(session2, sessions.get(0));
    assertEquals(session1, sessions.get(1));
  }

  @Test
  void sessionsIsolatedByUser() {
    memory.registerSession("alice", UUID.randomUUID());
    memory.registerSession("bob", UUID.randomUUID());

    assertEquals(1, memory.sessions("alice").size());
    assertEquals(1, memory.sessions("bob").size());
  }

  @Test
  void latestSessionForUnknownUserIsEmpty() {
    assertTrue(memory.latestSession("unknown").isEmpty());
  }

  @Test
  void sessionsForUnknownUserIsEmpty() {
    assertTrue(memory.sessions("unknown").isEmpty());
  }

  @Test
  void registerSessionIsIdempotent() {
    var session = UUID.randomUUID();

    memory.registerSession("alice", session);
    memory.registerSession("alice", session);

    assertEquals(1, memory.sessions("alice").size());
  }

  @Test
  void sessionsIsolatedByAgentId() {
    var otherMemory = new PgMemory(PgTestSupport.pgConfig("other-agent"));
    var session = UUID.randomUUID();

    memory.registerSession("alice", session);
    otherMemory.registerSession("alice", UUID.randomUUID());

    assertEquals(1, memory.sessions("alice").size());
    assertEquals(session, memory.sessions("alice").getFirst());
    assertEquals(1, otherMemory.sessions("alice").size());
  }
}
