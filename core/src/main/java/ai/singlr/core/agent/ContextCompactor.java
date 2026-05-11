/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.memory.MemoryListener;
import ai.singlr.core.model.Message;
import java.util.List;
import java.util.UUID;

/**
 * Strategy that keeps the conversation message list within a model's context window. The agent loop
 * calls {@link #compactIfNeeded} at the top of every iteration; implementations decide whether and
 * how to rewrite the list.
 *
 * <p>Default: {@link DefaultContextCompactor}, a four-phase algorithm modeled after Hermes Agent
 * production behaviour — prune oversized tool results, identify protected head/tail regions with
 * tool_call/tool_result boundary alignment, generate a structured-template summary of the middle
 * with iterative carryover, and sanitize orphaned tool calls/results.
 *
 * <p>For deployments that want to disable compaction entirely (short-lived agents, eval harnesses,
 * tests), pass {@link NoOpContextCompactor#INSTANCE} to {@code
 * AgentConfig.Builder.withContextCompactor}.
 *
 * <p>Implementations should fire {@link ai.singlr.core.memory.MemoryEvent.BeforeCompaction} via the
 * provided listener list <em>before</em> rewriting the message list, so external memory backends
 * can scan the full pre-compaction history for durable signals (file paths, critical decisions,
 * error patterns) and emit memory writes before the middle turns collapse.
 */
public interface ContextCompactor {

  /**
   * Convenience overload for callers that don't care about firing {@link
   * ai.singlr.core.memory.MemoryEvent.BeforeCompaction}. Equivalent to {@code compactIfNeeded(
   * messages, null, null, List.of())}.
   */
  default List<Message> compactIfNeeded(List<Message> messages) {
    return compactIfNeeded(messages, null, null, List.of());
  }

  /**
   * Return a compacted message list, or {@code messages} unchanged if no compaction is needed. When
   * the compactor decides to rewrite the list, it MUST fire {@link
   * ai.singlr.core.memory.MemoryEvent.BeforeCompaction} on every entry in {@code listeners} first,
   * passing the full pre-rewrite message list.
   *
   * @param messages the current message list
   * @param userId the user id for the active session (may be {@code null} when not session-scoped)
   * @param sessionId the session id (may be {@code null})
   * @param listeners listeners to notify with {@link
   *     ai.singlr.core.memory.MemoryEvent.BeforeCompaction} when compaction fires
   */
  List<Message> compactIfNeeded(
      List<Message> messages, String userId, UUID sessionId, List<MemoryListener> listeners);
}
