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

class TopicThreadingExtractorTest {

  private InMemoryMemory memory;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    memory = new InMemoryMemory();
    sessionId = UUID.randomUUID();
  }

  private MemoryEvent.AfterTurn turn(String userText) {
    return new MemoryEvent.AfterTurn(
        "u", sessionId, Message.user(userText), Message.assistant("ok"), List.of(), 0);
  }

  @Test
  void rejectsNullMemory() {
    assertThrows(IllegalArgumentException.class, () -> new TopicThreadingExtractor(null));
  }

  @Test
  void rejectsNonPositiveTopK() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TopicThreadingExtractor(memory, 0, 5, TopicThreadingExtractor.DEFAULT_STOPWORDS));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TopicThreadingExtractor(memory, -1, 5, TopicThreadingExtractor.DEFAULT_STOPWORDS));
  }

  @Test
  void rejectsNonPositiveFlushEvery() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TopicThreadingExtractor(memory, 5, 0, TopicThreadingExtractor.DEFAULT_STOPWORDS));
  }

  @Test
  void rejectsNullStopwords() {
    assertThrows(
        IllegalArgumentException.class, () -> new TopicThreadingExtractor(memory, 5, 5, null));
  }

  @Test
  void doesNotFlushBeforeThreshold() {
    var extractor = new TopicThreadingExtractor(memory, 3, 5, Set.of());

    extractor.onAfterTurn(turn("kubera portfolio analysis"));
    extractor.onAfterTurn(turn("portfolio risk and analysis"));
    extractor.onAfterTurn(turn("risk metrics"));
    extractor.onAfterTurn(turn("portfolio rebalance"));

    assertTrue(memory.block(MemoryBlocks.USER_PROFILE).isEmpty(), "no flush before 5 turns");
  }

  @Test
  void flushesAfterThreshold() {
    var extractor = new TopicThreadingExtractor(memory, 3, 3, Set.of());

    extractor.onAfterTurn(turn("kubera portfolio analysis"));
    extractor.onAfterTurn(turn("portfolio risk and analysis"));
    extractor.onAfterTurn(turn("risk metrics in analysis"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("analysis"));
    assertTrue(topics.contains("portfolio") || topics.contains("risk"));
  }

  @Test
  void stopwordsAreIgnored() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of("ignored"));

    extractor.onAfterTurn(turn("ignored ignored ignored important"));
    extractor.onAfterTurn(turn("ignored ignored ignored important"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("important"));
    assertTrue(!topics.contains("ignored"), "stopwords must not appear");
  }

  @Test
  void shortTokensAreDropped() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of());

    extractor.onAfterTurn(turn("ok hi yes longword"));
    extractor.onAfterTurn(turn("ok hi yes longword"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("longword"));
    assertTrue(!topics.contains("ok"), "3-char tokens must be dropped");
  }

  @Test
  void countsAreDeDupedPerTurn() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of());

    extractor.onAfterTurn(turn("portfolio portfolio portfolio"));
    extractor.onAfterTurn(turn("risk"));

    var snapshot = extractor.snapshot();
    assertEquals(1, snapshot.get("portfolio"));
  }

  @Test
  void onSessionEndForcesFlushOfPendingCounts() {
    var extractor = new TopicThreadingExtractor(memory, 5, 100, Set.of());

    extractor.onAfterTurn(turn("portfolio analysis"));
    assertTrue(memory.block(MemoryBlocks.USER_PROFILE).isEmpty(), "no flush yet");

    extractor.onSessionEnd(
        new MemoryEvent.SessionEnd(
            "u", sessionId, List.of(), MemoryEvent.SessionEnd.Termination.COMPLETED));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("portfolio") || topics.contains("analysis"));
  }

  @Test
  void onSessionEndWithEmptyCountsIsNoOp() {
    var extractor = new TopicThreadingExtractor(memory, 5, 100, Set.of());
    extractor.onSessionEnd(
        new MemoryEvent.SessionEnd(
            "u", sessionId, List.of(), MemoryEvent.SessionEnd.Termination.COMPLETED));
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void nullUserMessageIsNoOp() {
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    var ev = new MemoryEvent.AfterTurn("u", sessionId, null, Message.assistant("ok"), List.of(), 0);
    extractor.onAfterTurn(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void onlyReactsToUserMessages() {
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    var ev =
        new MemoryEvent.AfterTurn(
            "u",
            sessionId,
            Message.assistant("important analysis"),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onAfterTurn(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void existingUserProfilePreserved() {
    memory.putBlock(MemoryBlocks.userProfile().withValue("name", "Alice").build());
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    extractor.onAfterTurn(turn("portfolio analysis"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", profile.value("name"));
    assertTrue(profile.data().containsKey("recurring_topics"));
  }

  @Test
  void turnsSinceFlushResetsAfterFlush() {
    var extractor = new TopicThreadingExtractor(memory, 3, 2, Set.of());

    extractor.onAfterTurn(turn("token1"));
    extractor.onAfterTurn(turn("token2"));
    assertEquals(0, extractor.turnsSinceFlush());

    extractor.onAfterTurn(turn("token3"));
    assertEquals(1, extractor.turnsSinceFlush());
  }
}
