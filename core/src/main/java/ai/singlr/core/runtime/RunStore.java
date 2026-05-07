/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable store for {@link AgentRun} records. Implementations must be safe for concurrent use:
 * checkpoints from one thread and reads from another should not corrupt or lose state.
 *
 * <p>The {@link InMemoryRunStore} is suitable for tests and single-process deployments without
 * crash recovery requirements; use the Postgres-backed implementation in {@code helios-persistence}
 * for production durability.
 *
 * <p>Distributed multi-JVM coordination (worker leases, claim-by-skip-locked) is intentionally not
 * part of this interface in v1 — additive shape changes can introduce it without breaking
 * single-JVM users.
 */
public interface RunStore {

  /** Upsert the run keyed by {@link AgentRun#runId()}. */
  void checkpoint(AgentRun run);

  /** Look up a run by id. */
  Optional<AgentRun> find(UUID runId);

  /**
   * List runs in a given status, newest first. The implementation may bound the result; callers
   * should treat this as a recent-window view, not a full audit.
   */
  List<AgentRun> findByStatus(AgentRunStatus status);
}
