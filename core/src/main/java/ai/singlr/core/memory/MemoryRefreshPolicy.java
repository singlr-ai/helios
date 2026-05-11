/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.memory;

/**
 * Controls when {@code ${core_memory}} is re-rendered into the system prompt during a run. This is
 * a cache-vs-responsiveness tradeoff: re-rendering every iteration makes mid-run {@code
 * memory_update} calls visible to the model on the very next turn but invalidates Anthropic prefix
 * caching; re-rendering once per session keeps the system prompt cacheable but defers mid-run
 * memory mutations until the next session.
 *
 * <p>Choose by deployment profile:
 *
 * <ul>
 *   <li>{@link #PER_ITERATION} — default. Best when the model is expected to {@code memory_update}
 *       mid-run and act on the new state immediately (Kubera-style interactive research agents).
 *   <li>{@link #PER_SESSION} — best for stateless or near-stateless agents where the system prompt
 *       rarely changes and the cache hit is more valuable than mid-run responsiveness.
 * </ul>
 */
public enum MemoryRefreshPolicy {
  /**
   * Rebuild the system prompt at the top of every iteration when {@link Memory} content has
   * changed. Mid-run {@code memory_update} calls become visible to the model on the next turn at
   * the cost of invalidating the system-prompt prefix cache.
   */
  PER_ITERATION,

  /**
   * Build the system prompt once at session start and keep it stable for the duration of the run.
   * Mid-run {@code memory_update} writes still go to disk but do not flow into the prompt until the
   * next session reads them. Best for cache-sensitive deployments.
   */
  PER_SESSION
}
