/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory.behavior;

import ai.singlr.core.events.EventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.memory.Memory;
import ai.singlr.core.memory.MemoryBlocks;
import ai.singlr.core.model.Role;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reference {@link EventSink} that tracks recurring topics across user turns and surfaces the top-K
 * most frequent into {@link MemoryBlocks#USER_PROFILE}{@code .recurring_topics}.
 *
 * <p>Implementation: keep a frequency map of tokenized keywords (lowercased, stopwords removed,
 * length ≥ 4). On each turn the user message contributes its tokens. Every {@code flushEvery} turns
 * (default 5), the top {@code topK} keywords are written to the user_profile block as a
 * comma-separated string.
 *
 * <p>The extractor maintains state across the agent's lifetime — typically scoped per-user via the
 * caller's choice of {@link Memory} (e.g. a {@code PgMemory} namespaced as {@code
 * "agent:user-42"}). Caller is responsible for not sharing the extractor across users.
 *
 * <p>Like {@link ToolAcceptanceExtractor}, this is a heuristic pattern — production deployments may
 * want LLM-driven topic extraction. The hook contract is the same.
 */
public final class TopicThreadingExtractor implements EventSink {

  /** Default words to ignore — covers common English fillers, not exhaustive. */
  public static final Set<String> DEFAULT_STOPWORDS =
      Set.of(
          "the", "and", "for", "with", "from", "have", "this", "that", "these", "those", "would",
          "could", "should", "about", "what", "when", "where", "which", "your", "their", "they",
          "them", "into", "onto", "than", "then", "thus", "very", "much", "more", "less", "some",
          "many", "most", "also", "just", "only", "such", "even", "make", "made", "take", "took",
          "give", "gave", "tell", "told", "want", "need", "like", "look", "looks", "looking",
          "going", "back", "down", "over", "well", "okay");

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9-]{3,}");

  private final Memory memory;
  private final int topK;
  private final int flushEvery;
  private final Set<String> stopwords;
  private final Map<String, Integer> counts = new HashMap<>();
  private final AtomicInteger turnsSinceFlush = new AtomicInteger();

  public TopicThreadingExtractor(Memory memory) {
    this(memory, 8, 5, DEFAULT_STOPWORDS);
  }

  public TopicThreadingExtractor(Memory memory, int topK, int flushEvery, Set<String> stopwords) {
    if (memory == null) {
      throw new IllegalArgumentException("memory must not be null");
    }
    if (topK <= 0) {
      throw new IllegalArgumentException("topK must be > 0");
    }
    if (flushEvery <= 0) {
      throw new IllegalArgumentException("flushEvery must be > 0");
    }
    if (stopwords == null) {
      throw new IllegalArgumentException("stopwords must not be null");
    }
    this.memory = memory;
    this.topK = topK;
    this.flushEvery = flushEvery;
    this.stopwords = Set.copyOf(stopwords);
  }

  @Override
  public void onEvent(HeliosEvent event) {
    switch (event) {
      case HeliosEvent.AfterTurn afterTurn -> handleAfterTurn(afterTurn);
      case HeliosEvent.SessionEnd ignored ->
          // Flush whatever's pending so end-of-session signals get persisted.
          flush();
      default -> {
        /* not interested */
      }
    }
  }

  private void handleAfterTurn(HeliosEvent.AfterTurn event) {
    if (event.userMessage().isEmpty()) {
      return;
    }
    var msg = event.userMessage().get();
    if (msg.role() != Role.USER || msg.content() == null) {
      return;
    }
    var matches = TOKEN.matcher(msg.content().toLowerCase(Locale.ROOT));
    var perTurnSeen = new HashSet<String>();
    while (matches.find()) {
      var token = matches.group();
      if (stopwords.contains(token) || !perTurnSeen.add(token)) {
        continue;
      }
      counts.merge(token, 1, Integer::sum);
    }
    if (turnsSinceFlush.incrementAndGet() >= flushEvery) {
      turnsSinceFlush.set(0);
      flush();
    }
  }

  private void flush() {
    if (counts.isEmpty()) {
      return;
    }
    if (memory.block(MemoryBlocks.USER_PROFILE).isEmpty()) {
      memory.putBlock(MemoryBlocks.userProfile().build());
    }
    var topics =
        counts.entrySet().stream()
            .sorted(
                Map.Entry.<String, Integer>comparingByValue()
                    .reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
            .limit(topK)
            .map(Map.Entry::getKey)
            .collect(Collectors.joining(","));
    memory.updateBlock(MemoryBlocks.USER_PROFILE, "recurring_topics", topics);
  }

  /** Test seam — current count snapshot, copy. */
  Map<String, Integer> snapshot() {
    return new LinkedHashMap<>(counts);
  }

  /** Test seam — current count of turns processed since last flush. */
  int turnsSinceFlush() {
    return turnsSinceFlush.get();
  }
}
