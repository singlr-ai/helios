/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.memory.MemoryBlocks;
import ai.singlr.core.memory.MemoryEvent;
import ai.singlr.core.model.Message;
import java.util.List;
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

  private MemoryEvent.AfterTurn turn(String userText) {
    return new MemoryEvent.AfterTurn(
        "user-1", sessionId, Message.user(userText), Message.assistant("ok"), List.of(), 0);
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
    extractor.onAfterTurn(turn("how do I list files"));
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void avoidSignalRecordsTool() {
    extractor.onAfterTurn(turn("don't use ripgrep, it's too slow"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void preferSignalRecordsTool() {
    extractor.onAfterTurn(turn("always use kb_grep for code search"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("kb_grep", profile.value("preferred_tools"));
  }

  @Test
  void mergesMultipleToolsAcrossTurns() {
    extractor.onAfterTurn(turn("don't use ripgrep"));
    extractor.onAfterTurn(turn("avoid using fff"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var avoided = (String) profile.value("avoided_tools");
    assertTrue(avoided.contains("ripgrep"));
    assertTrue(avoided.contains("fff"));
  }

  @Test
  void unknownToolNamesAreIgnored() {
    extractor.onAfterTurn(turn("don't use git, it's slow"));
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void duplicateSignalsAreDedup() {
    extractor.onAfterTurn(turn("don't use ripgrep"));
    extractor.onAfterTurn(turn("stop using ripgrep"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void nullUserMessageDoesNothing() {
    var ev = new MemoryEvent.AfterTurn("u", sessionId, null, Message.assistant("ok"), List.of(), 0);
    extractor.onAfterTurn(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void nullContentDoesNothing() {
    var ev =
        new MemoryEvent.AfterTurn(
            "u", sessionId, Message.assistant(null, null), Message.assistant("ok"), List.of(), 0);
    extractor.onAfterTurn(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void caseInsensitiveDetection() {
    extractor.onAfterTurn(turn("DON'T USE RIPGREP"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertTrue(((String) profile.value("avoided_tools")).contains("ripgrep"));
  }

  @Test
  void existingUserProfileIsPreserved() {
    memory.putBlock(MemoryBlocks.userProfile().withValue("name", "Alice").build());
    extractor.onAfterTurn(turn("don't use ripgrep"));
    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", profile.value("name"));
    assertEquals("ripgrep", profile.value("avoided_tools"));
  }

  @Test
  void onlyReactsToUserMessages() {
    var ev =
        new MemoryEvent.AfterTurn(
            "u",
            sessionId,
            Message.assistant("don't use ripgrep"),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onAfterTurn(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }
}
