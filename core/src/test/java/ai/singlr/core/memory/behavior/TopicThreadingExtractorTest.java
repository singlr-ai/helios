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

class TopicThreadingExtractorTest {

  private InMemoryMemory memory;
  private UUID sessionId;

  @BeforeEach
  void setUp() {
    memory = new InMemoryMemory();
    sessionId = UUID.randomUUID();
  }

  private HeliosEvent.AfterTurn turn(String userText) {
    return new HeliosEvent.AfterTurn(
        Instant.now(),
        Ids.newId(),
        Optional.empty(),
        "u",
        sessionId,
        Optional.of(Message.user(userText)),
        Message.assistant("ok"),
        List.of(),
        0);
  }

  private HeliosEvent.SessionEnd sessionEndEvent() {
    return new HeliosEvent.SessionEnd(
        Instant.now(),
        Ids.newId(),
        Optional.empty(),
        "u",
        sessionId,
        List.of(),
        HeliosEvent.SessionEnd.Termination.COMPLETED);
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

    extractor.onEvent(turn("kubera portfolio analysis"));
    extractor.onEvent(turn("portfolio risk and analysis"));
    extractor.onEvent(turn("risk metrics"));
    extractor.onEvent(turn("portfolio rebalance"));

    assertTrue(memory.block(MemoryBlocks.USER_PROFILE).isEmpty(), "no flush before 5 turns");
  }

  @Test
  void flushesAfterThreshold() {
    var extractor = new TopicThreadingExtractor(memory, 3, 3, Set.of());

    extractor.onEvent(turn("kubera portfolio analysis"));
    extractor.onEvent(turn("portfolio risk and analysis"));
    extractor.onEvent(turn("risk metrics in analysis"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("analysis"));
    assertTrue(topics.contains("portfolio") || topics.contains("risk"));
  }

  @Test
  void stopwordsAreIgnored() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of("ignored"));

    extractor.onEvent(turn("ignored ignored ignored important"));
    extractor.onEvent(turn("ignored ignored ignored important"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("important"));
    assertTrue(!topics.contains("ignored"), "stopwords must not appear");
  }

  @Test
  void shortTokensAreDropped() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of());

    extractor.onEvent(turn("ok hi yes longword"));
    extractor.onEvent(turn("ok hi yes longword"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("longword"));
    assertTrue(!topics.contains("ok"), "3-char tokens must be dropped");
  }

  @Test
  void countsAreDeDupedPerTurn() {
    var extractor = new TopicThreadingExtractor(memory, 5, 2, Set.of());

    extractor.onEvent(turn("portfolio portfolio portfolio"));
    extractor.onEvent(turn("risk"));

    var snapshot = extractor.snapshot();
    assertEquals(1, snapshot.get("portfolio"));
  }

  @Test
  void onSessionEndForcesFlushOfPendingCounts() {
    var extractor = new TopicThreadingExtractor(memory, 5, 100, Set.of());

    extractor.onEvent(turn("portfolio analysis"));
    assertTrue(memory.block(MemoryBlocks.USER_PROFILE).isEmpty(), "no flush yet");

    extractor.onEvent(sessionEndEvent());

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    var topics = (String) profile.value("recurring_topics");
    assertTrue(topics.contains("portfolio") || topics.contains("analysis"));
  }

  @Test
  void onSessionEndWithEmptyCountsIsNoOp() {
    var extractor = new TopicThreadingExtractor(memory, 5, 100, Set.of());
    extractor.onEvent(sessionEndEvent());
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void emptyUserMessageIsNoOp() {
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
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
  void onlyReactsToUserMessages() {
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    var ev =
        new HeliosEvent.AfterTurn(
            Instant.now(),
            Ids.newId(),
            Optional.empty(),
            "u",
            sessionId,
            Optional.of(Message.assistant("important analysis")),
            Message.assistant("ok"),
            List.of(),
            0);
    extractor.onEvent(ev);
    assertTrue(memory.coreBlocks().isEmpty());
  }

  @Test
  void existingUserProfilePreserved() {
    memory.putBlock(MemoryBlocks.userProfile().withValue("name", "Alice").build());
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    extractor.onEvent(turn("portfolio analysis"));

    var profile = memory.block(MemoryBlocks.USER_PROFILE).orElseThrow();
    assertEquals("Alice", profile.value("name"));
    assertTrue(profile.data().containsKey("recurring_topics"));
  }

  @Test
  void turnsSinceFlushResetsAfterFlush() {
    var extractor = new TopicThreadingExtractor(memory, 3, 2, Set.of());

    extractor.onEvent(turn("token1"));
    extractor.onEvent(turn("token2"));
    assertEquals(0, extractor.turnsSinceFlush());

    extractor.onEvent(turn("token3"));
    assertEquals(1, extractor.turnsSinceFlush());
  }

  @Test
  void ignoresEventsOtherThanAfterTurnAndSessionEnd() {
    var extractor = new TopicThreadingExtractor(memory, 5, 1, Set.of());
    extractor.onEvent(
        new HeliosEvent.AssistantText(Instant.now(), Ids.newId(), Optional.empty(), "anything"));
    assertTrue(memory.coreBlocks().isEmpty());
  }
}
