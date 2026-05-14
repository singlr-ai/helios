/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.Message;
import java.util.ArrayList;
import java.util.List;
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
    assertTrue(memory.block("persona").isPresent());
    assertTrue(memory.block("user").isPresent());
  }

  @Test
  void coreBlocksReturnsBlocksSortedByName() {
    memory = new InMemoryMemory();
    memory.putBlock(MemoryBlock.newBuilder().withName("zeta").build());
    memory.putBlock(MemoryBlock.newBuilder().withName("alpha").build());
    memory.putBlock(MemoryBlock.newBuilder().withName("mu").build());

    var names = memory.coreBlocks().stream().map(MemoryBlock::name).toList();
    assertEquals(List.of("alpha", "mu", "zeta"), names);
  }

  @Test
  void blockReturnsEmptyForUnknownName() {
    assertTrue(memory.block("nonexistent").isEmpty());
  }

  @Test
  void updateBlock() {
    memory.updateBlock("user", "name", "Alice");

    var block = memory.block("user").orElseThrow();
    assertEquals("Alice", block.value("name"));
  }

  @Test
  void replaceBlock() {
    memory.updateBlock("user", "name", "Alice");
    memory.replaceBlock("user", Map.of("name", "Bob", "age", 25));

    var block = memory.block("user").orElseThrow();
    assertEquals("Bob", block.value("name"));
    assertEquals(25, block.<Integer>value("age"));
  }

  @Test
  void removeBlockReturnsTrueWhenPresent() {
    assertTrue(memory.removeBlock("user"));
    assertTrue(memory.block("user").isEmpty());
  }

  @Test
  void removeBlockReturnsFalseWhenAbsent() {
    assertFalse(memory.removeBlock("nonexistent"));
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
    memory.addMessage(null, sessionId, Message.user("Hello"));
    memory.addMessage(null, sessionId, Message.assistant("Hi there!"));
    memory.addMessage(null, sessionId, Message.user("How are you?"));

    var history = memory.history(null, sessionId);
    assertEquals(3, history.size());
    assertEquals("Hello", history.get(0).content());
  }

  @Test
  void searchHistory() {
    memory.addMessage(null, sessionId, Message.user("What's the weather?"));
    memory.addMessage(null, sessionId, Message.assistant("It's sunny."));
    memory.addMessage(null, sessionId, Message.user("Tell me a joke."));

    var results = memory.searchHistory(null, sessionId, "weather", 10);
    assertEquals(1, results.size());
    assertTrue(results.getFirst().content().contains("weather"));
  }

  @Test
  void clearHistory() {
    memory.addMessage(null, sessionId, Message.user("Hello"));
    memory.clearHistory(null, sessionId);

    assertTrue(memory.history(null, sessionId).isEmpty());
  }

  @Test
  void renderCoreMemory() {
    memory.updateBlock("persona", "role", "assistant");
    memory.updateBlock("user", "name", "Alice");

    var rendered = memory.renderCoreMemory();

    assertTrue(
        rendered.startsWith("[The following blocks are persistent state."),
        "guardrail header must precede fenced blocks");
    assertTrue(rendered.contains("<core-memory-block name=\"persona\">"));
    assertTrue(rendered.contains("role: assistant"));
    assertTrue(rendered.contains("<core-memory-block name=\"user\">"));
    assertTrue(rendered.contains("name: Alice"));
    assertTrue(rendered.contains("</core-memory-block>"));
  }

  @Test
  void renderCoreMemoryEmptyWhenNoBlocks() {
    var empty = new InMemoryMemory();
    assertTrue(empty.renderCoreMemory().isEmpty());
  }

  @Test
  void withDefaultsInstallsCanonicalBlocks() {
    var defaultMemory = InMemoryMemory.withDefaults();

    assertTrue(defaultMemory.block(MemoryBlocks.IDENTITY).isPresent());
    assertTrue(defaultMemory.block(MemoryBlocks.USER_PROFILE).isPresent());
    assertTrue(defaultMemory.block(MemoryBlocks.WORKING_MEMORY).isPresent());
    assertEquals(3, defaultMemory.coreBlocks().size());
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
    memory.addMessage(null, sessionId, Message.user("Hello"));
    memory.addMessage(null, sessionId, Message.assistant("Hi"));

    var results = memory.searchHistory(null, sessionId, "", 10);

    assertEquals(2, results.size());
  }

  @Test
  void searchHistoryWithNullQuery() {
    memory.addMessage(null, sessionId, Message.user("Hello"));

    var results = memory.searchHistory(null, sessionId, null, 10);

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

    assertTrue(customMemory.block("custom").isPresent());
    assertEquals("value", customMemory.block("custom").orElseThrow().value("key"));
  }

  @Test
  void withBlockNameDescriptionMaxSize() {
    var customMemory =
        InMemoryMemory.newBuilder().withBlock("scratch", "Working area", 4096).build();

    var block = customMemory.block("scratch").orElseThrow();
    assertEquals("Working area", block.description());
    assertEquals(4096, block.maxSize());
  }

  @Test
  void putBlock() {
    var block = MemoryBlock.newBuilder().withName("newblock").withDescription("New block").build();

    memory.putBlock(block);

    assertTrue(memory.block("newblock").isPresent());
    assertEquals(3, memory.coreBlocks().size());
  }

  @Test
  void putBlockOverwritesExisting() {
    var v1 = MemoryBlock.newBuilder().withName("k").withValue("a", "1").build();
    var v2 = MemoryBlock.newBuilder().withName("k").withValue("a", "2").build();
    memory.putBlock(v1);
    memory.putBlock(v2);

    assertEquals("2", memory.block("k").orElseThrow().value("a"));
  }

  @Test
  void searchHistorySkipsNullContent() {
    memory.addMessage(null, sessionId, Message.assistant(null, null));
    memory.addMessage(null, sessionId, Message.user("Has content"));

    var results = memory.searchHistory(null, sessionId, "content", 10);

    assertEquals(1, results.size());
  }

  @Test
  void sessionsAreIsolated() {
    var session1 = Ids.newId();
    var session2 = Ids.newId();

    memory.addMessage(null, session1, Message.user("Session 1 message"));
    memory.addMessage(null, session2, Message.user("Session 2 message"));

    assertEquals(1, memory.history(null, session1).size());
    assertEquals(1, memory.history(null, session2).size());
    assertEquals("Session 1 message", memory.history(null, session1).getFirst().content());
    assertEquals("Session 2 message", memory.history(null, session2).getFirst().content());
  }

  @Test
  void historyForUnknownSessionIsEmpty() {
    assertTrue(memory.history(null, Ids.newId()).isEmpty());
  }

  @Test
  void searchHistoryForUnknownSessionIsEmpty() {
    assertTrue(memory.searchHistory(null, Ids.newId(), "anything", 10).isEmpty());
  }

  @Test
  void registerSessionAndFindLatest() {
    var session1 = Ids.newId();
    var session2 = Ids.newId();

    memory.registerSession("alice", session1);
    memory.registerSession("alice", session2);

    var latest = memory.latestSession("alice");
    assertTrue(latest.isPresent());
    assertEquals(session2, latest.get());
  }

  @Test
  void sessionsReturnsAllForUserMostRecentFirst() {
    var session1 = Ids.newId();
    var session2 = Ids.newId();
    var session3 = Ids.newId();

    memory.registerSession("bob", session1);
    memory.registerSession("bob", session2);
    memory.registerSession("bob", session3);

    var sessions = memory.sessions("bob");
    assertEquals(3, sessions.size());
    assertEquals(session3, sessions.get(0));
    assertEquals(session2, sessions.get(1));
    assertEquals(session1, sessions.get(2));
  }

  @Test
  void sessionsIsolatedByUser() {
    var aliceSession = Ids.newId();
    var bobSession = Ids.newId();

    memory.registerSession("alice", aliceSession);
    memory.registerSession("bob", bobSession);

    assertEquals(1, memory.sessions("alice").size());
    assertEquals(aliceSession, memory.sessions("alice").getFirst());
    assertEquals(1, memory.sessions("bob").size());
    assertEquals(bobSession, memory.sessions("bob").getFirst());
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
    var session = Ids.newId();

    memory.registerSession("alice", session);
    memory.registerSession("alice", session);

    assertEquals(1, memory.sessions("alice").size());
    assertEquals(session, memory.latestSession("alice").get());
  }

  @Test
  void reRegistrationUpdatesLastActive() {
    var oldSession = Ids.newId();
    var newSession = Ids.newId();

    memory.registerSession("alice", oldSession);
    memory.registerSession("alice", newSession);
    memory.registerSession("alice", oldSession);

    assertEquals(oldSession, memory.latestSession("alice").get());
  }

  @Test
  void historyIsolatedByUserId() {
    var session = Ids.newId();

    memory.addMessage("alice", session, Message.user("Alice's message"));
    memory.addMessage("bob", session, Message.user("Bob's message"));

    var aliceHistory = memory.history("alice", session);
    var bobHistory = memory.history("bob", session);

    assertEquals(1, aliceHistory.size());
    assertEquals("Alice's message", aliceHistory.getFirst().content());
    assertEquals(1, bobHistory.size());
    assertEquals("Bob's message", bobHistory.getFirst().content());
  }

  @Test
  void clearHistoryIsolatedByUserId() {
    var session = Ids.newId();

    memory.addMessage("alice", session, Message.user("Alice's message"));
    memory.addMessage("bob", session, Message.user("Bob's message"));

    memory.clearHistory("alice", session);

    assertTrue(memory.history("alice", session).isEmpty());
    assertEquals(1, memory.history("bob", session).size());
  }

  @Test
  void searchHistoryIsolatedByUserId() {
    var session = Ids.newId();

    memory.addMessage("alice", session, Message.user("Hello from Alice"));
    memory.addMessage("bob", session, Message.user("Hello from Bob"));

    var aliceResults = memory.searchHistory("alice", session, "Hello", 10);
    var bobResults = memory.searchHistory("bob", session, "Hello", 10);

    assertEquals(1, aliceResults.size());
    assertTrue(aliceResults.getFirst().content().contains("Alice"));
    assertEquals(1, bobResults.size());
    assertTrue(bobResults.getFirst().content().contains("Bob"));
  }

  @Test
  void wrongUserIdReturnsEmptyHistory() {
    var session = Ids.newId();

    memory.addMessage("alice", session, Message.user("Secret message"));

    assertTrue(memory.history("bob", session).isEmpty());
    assertTrue(memory.searchHistory("bob", session, "Secret", 10).isEmpty());
  }

  // --- Event sink lifecycle (MemoryWritten events) ---------------------------------------------

  @Test
  void putBlockEmitsMemoryWrittenEventToSinks() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.putBlock(MemoryBlock.newBuilder().withName("listener_test").build());

    assertEquals(1, received.size());
    var event = (HeliosEvent.MemoryWritten) received.getFirst();
    assertEquals("put", event.operation());
    assertEquals("listener_test", event.blockName());
  }

  @Test
  void updateBlockEmitsMemoryWritten() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.updateBlock("user", "name", "Alice");

    assertTrue(
        received.stream()
            .filter(HeliosEvent.MemoryWritten.class::isInstance)
            .map(HeliosEvent.MemoryWritten.class::cast)
            .anyMatch(e -> "update".equals(e.operation())));
  }

  @Test
  void replaceBlockEmitsMemoryWritten() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.replaceBlock("user", Map.of("k", "v"));

    assertTrue(
        received.stream()
            .filter(HeliosEvent.MemoryWritten.class::isInstance)
            .map(HeliosEvent.MemoryWritten.class::cast)
            .anyMatch(e -> "replace".equals(e.operation())));
  }

  @Test
  void removeBlockEmitsMemoryWritten() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.removeBlock("user");

    assertTrue(
        received.stream()
            .filter(HeliosEvent.MemoryWritten.class::isInstance)
            .map(HeliosEvent.MemoryWritten.class::cast)
            .anyMatch(e -> "remove".equals(e.operation())));
  }

  @Test
  void removeBlockMissNeverEmits() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.removeBlock("nonexistent");

    assertTrue(received.isEmpty());
  }

  @Test
  void archiveEmitsMemoryWrittenWithArchiveOperation() {
    var received = new ArrayList<HeliosEvent>();
    memory.addEventSink(received::add);

    memory.archive("hello", Map.of("tag", "x"));

    var ev =
        received.stream()
            .filter(HeliosEvent.MemoryWritten.class::isInstance)
            .map(HeliosEvent.MemoryWritten.class::cast)
            .filter(e -> "archive".equals(e.operation()))
            .findFirst()
            .orElseThrow();
    assertEquals("__archive__", ev.blockName());
  }

  @Test
  void archiveWithNullMetadataDoesNotThrow() {
    memory.archive("note", null);
    assertEquals(1, memory.searchArchive("note", 10).size());
  }

  @Test
  void addEventSinkIsIdempotent() {
    var counter = new int[] {0};
    var sink = (ai.singlr.core.events.EventSink) e -> counter[0]++;
    memory.addEventSink(sink);
    memory.addEventSink(sink);

    memory.updateBlock("user", "k", "v");

    assertEquals(1, counter[0]);
  }

  @Test
  void removeEventSinkStopsDispatch() {
    var counter = new int[] {0};
    var sink = (ai.singlr.core.events.EventSink) e -> counter[0]++;
    memory.addEventSink(sink);
    memory.updateBlock("user", "k", "v1");
    memory.removeEventSink(sink);
    memory.updateBlock("user", "k", "v2");

    assertEquals(1, counter[0]);
  }

  @Test
  void addEventSinkRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> memory.addEventSink(null));
  }

  @Test
  void sinkExceptionDoesNotAbortWrite() {
    memory.addEventSink(
        e -> {
          throw new RuntimeException("sink exploded");
        });

    // Must not throw; the write should still land.
    memory.updateBlock("user", "k", "v");
    assertEquals("v", memory.block("user").orElseThrow().value("k"));
  }

  @Test
  void blockReturnsEmptyForNullName() {
    assertTrue(new InMemoryMemory().block("anything-missing").isEmpty());
    assertNotNull(new InMemoryMemory());
  }

  // --- purgeSessionsOlderThan ----------------------------------------------------------------

  @Test
  void purgeSessionsRejectsNullDuration() {
    assertThrows(IllegalArgumentException.class, () -> memory.purgeSessionsOlderThan(null));
  }

  @Test
  void purgeSessionsRejectsNegativeDuration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> memory.purgeSessionsOlderThan(java.time.Duration.ofSeconds(-1)));
  }

  @Test
  void purgeSessionsOnEmptyRegistryReturnsZero() {
    assertEquals(0, memory.purgeSessionsOlderThan(java.time.Duration.ZERO));
  }

  @Test
  void purgeSessionsRetainsRecentSessions() throws InterruptedException {
    var s1 = Ids.newId();
    memory.registerSession("alice", s1);
    memory.addMessage("alice", s1, Message.user("recent"));

    // Allow a small wall-clock delta to pass, then ask for sessions older than 1 hour — none match.
    Thread.sleep(5);

    assertEquals(0, memory.purgeSessionsOlderThan(java.time.Duration.ofHours(1)));
    assertEquals(1, memory.history("alice", s1).size());
    assertTrue(memory.latestSession("alice").isPresent());
  }

  @Test
  void purgeSessionsRemovesStaleSessionAndCascadesMessages() throws InterruptedException {
    var stale = Ids.newId();
    var fresh = Ids.newId();
    memory.registerSession("alice", stale);
    memory.addMessage("alice", stale, Message.user("old1"));
    memory.addMessage("alice", stale, Message.assistant("old2"));

    // Wait so the stale session's lastActiveAt falls strictly before "now - 10ms".
    Thread.sleep(30);
    memory.registerSession("alice", fresh);
    memory.addMessage("alice", fresh, Message.user("new1"));

    var removed = memory.purgeSessionsOlderThan(java.time.Duration.ofMillis(10));

    assertEquals(1, removed);
    assertTrue(
        memory.history("alice", stale).isEmpty(),
        "Cascade: messages for the purged session must be gone");
    assertEquals(1, memory.history("alice", fresh).size(), "Fresh session is untouched");
    assertEquals(fresh, memory.latestSession("alice").orElseThrow());
  }

  @Test
  void purgeSessionsHandlesNullUserId() throws InterruptedException {
    var anon = Ids.newId();
    memory.registerSession(null, anon);
    memory.addMessage(null, anon, Message.user("anonymous"));

    Thread.sleep(15);

    assertEquals(1, memory.purgeSessionsOlderThan(java.time.Duration.ofMillis(5)));
    assertTrue(memory.history(null, anon).isEmpty());
  }
}
