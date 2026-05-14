/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import ai.singlr.core.events.EventSink;
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
 * <p>Implementations emit {@link ai.singlr.core.events.HeliosEvent.BeforeCompaction} on every
 * registered {@link EventSink} <em>before</em> rewriting the message list, so external consumers
 * can scan the full pre-compaction history for durable signals (file paths, critical decisions,
 * error patterns) and react before the middle turns collapse. On successful compaction they
 * additionally emit {@link ai.singlr.core.events.HeliosEvent.CompactionTriggered}.
 */
public interface ContextCompactor {

  /**
   * Convenience overload for callers that don't care about emitting events. Equivalent to {@code
   * compactIfNeeded(messages, null, null, null, List.of())}.
   */
  default List<Message> compactIfNeeded(List<Message> messages) {
    return compactIfNeeded(messages, null, null, null, List.of());
  }

  /**
   * Return a compacted message list, or {@code messages} unchanged if no compaction is needed. When
   * the compactor decides to rewrite the list, it MUST emit {@link
   * ai.singlr.core.events.HeliosEvent.BeforeCompaction} to every entry in {@code eventSinks} first,
   * passing the full pre-rewrite message list.
   *
   * @param messages the current message list
   * @param runId the agent run's id (UUID v7); may be {@code null} when not run-scoped
   * @param userId the user id for the active session (may be {@code null} when not session-scoped)
   * @param sessionId the session id (may be {@code null})
   * @param eventSinks sinks that receive {@link ai.singlr.core.events.HeliosEvent.BeforeCompaction}
   *     (pre-rewrite) and {@link ai.singlr.core.events.HeliosEvent.CompactionTriggered}
   *     (post-rewrite) when compaction fires
   */
  List<Message> compactIfNeeded(
      List<Message> messages,
      UUID runId,
      String userId,
      UUID sessionId,
      List<EventSink> eventSinks);
}
