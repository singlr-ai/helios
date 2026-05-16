/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.tool;

import ai.singlr.core.runtime.CancellationToken;
import java.time.Duration;
import java.util.Objects;

/**
 * Per-invocation context handed to a {@link ToolExecutor}. Carries the session's {@link
 * CancellationToken} so long-running tool work can poll {@link CancellationToken#isCancelled()} or
 * {@link CancellationToken#throwIfCancelled()} at safe points, plus the per-call {@code deadline}
 * the dispatcher will enforce as a wall-clock timeout.
 *
 * <p>The dispatcher is responsible for the hard timeout (it runs the executor on a virtual thread
 * and abandons it after {@code deadline}). Tools that observe {@link #deadline()} can shape their
 * work — e.g. stop a tree walk early — but they do not need to enforce it; missing the deadline is
 * not a correctness bug, only an efficiency one.
 *
 * <p>Use {@link #noop()} for call sites that don't have a real session context (tests, direct
 * library use, the v1 {@code Agent} loop in its own retry envelope).
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. The wrapped {@link CancellationToken} is itself thread-safe.
 *
 * @param cancellation the session's cancellation signal; non-null
 * @param deadline the wall-clock window the dispatcher will allow before abandoning the executor;
 *     non-null and non-negative
 */
public record ToolContext(CancellationToken cancellation, Duration deadline) {

  private static final ToolContext NOOP =
      new ToolContext(new CancellationToken(), Duration.ofDays(1));

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code cancellation} or {@code deadline} is null
   * @throws IllegalArgumentException if {@code deadline} is negative
   */
  public ToolContext {
    Objects.requireNonNull(cancellation, "cancellation must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    if (deadline.isNegative()) {
      throw new IllegalArgumentException("deadline must not be negative, got " + deadline);
    }
  }

  /**
   * The shared "no real context" instance — fresh, never-cancelled token and a generous deadline.
   * Use for tests and direct library callers that don't have a session.
   *
   * @return a stable noop context
   */
  public static ToolContext noop() {
    return NOOP;
  }

  /**
   * Build a context with the given cancellation and deadline.
   *
   * @param cancellation non-null cancellation
   * @param deadline non-null non-negative deadline
   * @return a fresh context
   */
  public static ToolContext of(CancellationToken cancellation, Duration deadline) {
    return new ToolContext(cancellation, deadline);
  }
}
