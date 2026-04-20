/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

/**
 * Captures and restores candidate state in an autoresearch loop.
 *
 * <p>A checkpoint is how the loop implements "keep" and "discard" decisions without committing to a
 * specific persistence mechanism. Before proposing a candidate the loop calls {@link #snapshot} to
 * record the current state. After the objective scores the candidate, the loop either leaves the
 * new state in place (keep) or calls {@link #restore} with the earlier snapshot (discard).
 *
 * <p>The snapshot type {@code C} is up to the implementation. For reference-type candidates
 * (strings, config records) an in-memory copy is sufficient — see {@link InMemoryCheckpoint}. For
 * filesystem candidates implementations may return a git commit hash, a tarball path, or any other
 * handle that {@link #restore} knows how to apply.
 *
 * <p>Implementations must be thread-safe if used across concurrent objective evaluations.
 *
 * @param <C> the candidate/snapshot type
 */
public interface Checkpoint<C> {

  /**
   * Capture the current state.
   *
   * @return a snapshot of the current state
   */
  C snapshot();

  /**
   * Restore a previously captured state.
   *
   * @param snapshot the snapshot returned by an earlier call to {@link #snapshot}
   */
  void restore(C snapshot);
}
