/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Ids;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.memory.MemoryBlocks;
import ai.singlr.core.model.Message;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolAcceptanceExtractorTest {

  private InMemoryMemory memory;
  private ToolAcceptanceExtractor extractor;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    memory = new InMemoryMemory();
    extractor = new ToolAcceptanceExtractor(memory, Set.of("ripgrep", "kb_grep", "fff"));
    sessionId = UUID.randomUUID();
  }

  private HeliosEvent.AfterTurn turn(String userText) {
    return new HeliosEvent.AfterTurn(
        Instant.now(),
        Ids.newId(),
        Optional.empty(),
        "user-1",
        sessionId,
        Optional.of(Message.user(userText)),
        Message.assistant("ok"),
        List.of(),
        0);
  }

  @Test
  void rejectsNullMemory() {
    assertThrows(IllegalArgumentException.class, () -> new ToolAcceptanceExtractor(null, Set.of()));
  }

  @Test
  void rejectsNullToolSet() {
    assertThrows(IllegalArgumentException.class, () -> new ToolAcceptanceExtractor(memory, null));
  }

  @Test
  void noSignalLeavesMemoryUnchanged() {
    extractor.onEvent(turn("how do I list files"));
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void avoidSignalRecordsTool() {
    extractor.onEvent(turn("don't use ripgrep, it's too slow"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void preferSignalRecordsTool() {
    extractor.onEvent(turn("always use kb_grep for code search"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("kb_grep", profile.value("preferred_tools"));
  }

  @Test
  void mergesMultipleToolsAcrossTurns() {
    extractor.onEvent(turn("don't use ripgrep"));
    extractor.onEvent(turn("avoid using fff"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var avoided = (String) profile.value("avoided_tools");
    assertTrue(avoided.contains("ripgrep"));
    assertTrue(avoided.contains("fff"));
  }

  @Test
  void unknownToolNamesAreIgnored() {
    extractor.onEvent(turn("don't use git, it's slow"));
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void duplicateSignalsAreDedup() {
    extractor.onEvent(turn("don't use ripgrep"));
    extractor.onEvent(turn("stop using ripgrep"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void emptyUserMessageDoesNothing() {
    var ev =
        new HeliosEvent.AfterTurn(
            Instant.now(),
            Ids.newId(),
            Optional.empty(),
            "u",
            sessionId,
            Optional.empty(),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onEvent(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void nullContentDoesNothing() {
    var ev =
        new HeliosEvent.AfterTurn(
            Instant.now(),
            Ids.newId(),
            Optional.empty(),
            "u",
            sessionId,
            Optional.of(Message.assistant(null, null)),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onEvent(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void caseInsensitiveDetection() {
    extractor.onEvent(turn("DON'T USE RIPGREP"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertTrue(((String) profile.value("avoided_tools")).contains("ripgrep"));
  }

  @Test
  void existingUserProfileIsPreserved() {
    memory.putBlock(MemoryBlocks.userProfile().withValue("name", "Alice").build());
    extractor.onEvent(turn("don't use ripgrep"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", profile.value("name"));
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void onlyReactsToUserMessages() {
    var ev =
        new HeliosEvent.AfterTurn(
            Instant.now(),
            Ids.newId(),
            Optional.empty(),
            "u",
            sessionId,
            Optional.of(Message.assistant("don't use ripgrep")),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onEvent(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void ignoresEventsOtherThanAfterTurn() {
    extractor.onEvent(
        new HeliosEvent.AssistantText(Instant.now(), Ids.newId(), Optional.empty(), "anything"));
    assertTrue(memory.coreBlocks().isEmpty());
  }
}
