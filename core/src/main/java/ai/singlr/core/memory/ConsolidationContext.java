/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

import ai.singlr.core.model.Message;
import java.util.List;

/**
 * Input passed to {@link MemoryConsolidator#consolidate}. Bundles the agent/user identity, a
 * snapshot of the recent conversation history, and a reference to the live {@link Memory} so the
 * consolidator can read existing blocks before producing suggestions.
 *
 * <p>The consolidator must not mutate {@code memory} directly — {@link ConsolidationReport#apply}
 * is the only sanctioned path so all writes flow through the same audit channel.
 *
 * @param agentId the agent identifier (matches the memory's {@code agent_id} namespace)
 * @param userId the user id this consolidation is scoped to; may be {@code null} for agent-global
 *     consolidation
 * @param memory the live memory store — read-only from the consolidator's perspective
 * @param recentHistory the conversation messages to consolidate over
 */
public record ConsolidationContext(
    String agentId, String userId, Memory memory, List<Message> recentHistory) {

  public ConsolidationContext {
    if (memory == null) {
      throw new IllegalArgumentException("memory must not be null");
    }
    if (recentHistory == null) {
      throw new IllegalArgumentException("recentHistory must not be null");
    }
    recentHistory = List.copyOf(recentHistory);
  }
}
