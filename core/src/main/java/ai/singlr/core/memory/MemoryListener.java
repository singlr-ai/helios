/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

/**
 * Receives {@link MemoryEvent}s fired by the agent loop and memory implementations. All methods
 * have no-op defaults, so implementations override only the events they care about.
 *
 * <p>Common patterns:
 *
 * <ul>
 *   <li><b>Behavior extractors</b> override {@link #onAfterTurn} to scan each user/assistant pair
 *       for signals (tool acceptance, recurring themes, style preferences) and call back into
 *       {@link Memory#updateBlock} to record them under a {@code user_profile} block.
 *   <li><b>External mirrors</b> override {@link #onMemoryWrite} to replicate writes to a vector
 *       store, search index, or audit log.
 *   <li><b>Consolidators</b> override {@link #onSessionEnd} to kick off a "dreaming" pass over the
 *       finished session — see {@link MemoryConsolidator}.
 *   <li><b>Prefetchers</b> override {@link #onBeforeApiCall} to inject recalled context (typically
 *       via {@link Memory#updateBlock} on a {@code working_memory} block before the call) — note
 *       that with {@link MemoryRefreshPolicy#PER_ITERATION} the next iteration sees the update on
 *       the same run, while with {@link MemoryRefreshPolicy#PER_SESSION} it lands next session.
 * </ul>
 *
 * <p><b>Threading.</b> All handlers run on the agent loop's calling thread. Implementations MUST
 * NOT block on network or LLM calls inside a handler — push such work onto a daemon thread or
 * virtual thread internally. Exceptions thrown from handlers are caught by the dispatcher and
 * logged at {@code WARNING}; they never abort the run.
 */
public interface MemoryListener {

  /** Fired before each {@code model.chat} call. See {@link MemoryEvent.BeforeApiCall}. */
  default void onBeforeApiCall(MemoryEvent.BeforeApiCall event) {}

  /** Fired after each completed turn. See {@link MemoryEvent.AfterTurn}. */
  default void onAfterTurn(MemoryEvent.AfterTurn event) {}

  /**
   * Fired before {@link ai.singlr.core.agent.ContextCompactor} rewrites the message list. See
   * {@link MemoryEvent.BeforeCompaction}.
   */
  default void onBeforeCompaction(MemoryEvent.BeforeCompaction event) {}

  /** Fired when the agent loop reaches a terminal state. See {@link MemoryEvent.SessionEnd}. */
  default void onSessionEnd(MemoryEvent.SessionEnd event) {}

  /** Fired on every memory mutation. See {@link MemoryEvent.MemoryWrite}. */
  default void onMemoryWrite(MemoryEvent.MemoryWrite event) {}
}
