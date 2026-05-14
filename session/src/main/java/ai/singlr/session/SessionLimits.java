/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-session ceilings the agent loop enforces.
 *
 * <p>Five orthogonal limits — turns, dollars, wall-clock, per-tool timeout, context tokens — each
 * mapping to a distinct termination path in {@link ResultMessage}. Limits are immutable for the
 * lifetime of a session; runtime mutation would let a long-running session escape the contract its
 * caller saw at construction.
 *
 * <p>Defaults track Helios production values:
 *
 * <ul>
 *   <li>{@code maxTurns}: 100 — covers any practical multi-step task without sponsoring runaway
 *       loops.
 *   <li>{@code maxBudgetUsd}: unset — opt in only; production deployments typically enforce budget
 *       in the control plane rather than the SDK.
 *   <li>{@code maxWallClock}: 1 hour — every long-running task we've shipped finished well inside
 *       this window.
 *   <li>{@code toolTimeoutDefault}: 2 minutes — slow enough for compile-and-run flows, fast enough
 *       that a wedged tool doesn't burn the wall-clock budget.
 *   <li>{@code maxContextTokens}: 180_000 — soft trigger for compaction; the loop reads this to
 *       decide when to compact, not to hard-fail.
 * </ul>
 *
 * @param maxTurns hard ceiling on agent-loop iterations; must be positive
 * @param maxBudgetUsd optional USD spend ceiling; if present, must be strictly positive
 * @param maxWallClock wall-clock ceiling from session start to terminal state; must be non-null and
 *     strictly positive
 * @param toolTimeoutDefault default per-tool execution timeout; must be non-null and strictly
 *     positive
 * @param maxContextTokens soft trigger for context compaction in tokens; must be positive
 */
public record SessionLimits(
    int maxTurns,
    Optional<BigDecimal> maxBudgetUsd,
    Duration maxWallClock,
    Duration toolTimeoutDefault,
    long maxContextTokens) {

  private static final SessionLimits DEFAULTS =
      new SessionLimits(
          100, Optional.empty(), Duration.ofHours(1), Duration.ofMinutes(2), 180_000L);

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code maxBudgetUsd}, {@code maxWallClock}, or {@code
   *     toolTimeoutDefault} is null
   * @throws IllegalArgumentException if any numeric value violates its positivity contract
   */
  public SessionLimits {
    if (maxTurns <= 0) {
      throw new IllegalArgumentException("maxTurns must be positive, got " + maxTurns);
    }
    Objects.requireNonNull(maxBudgetUsd, "maxBudgetUsd must not be null");
    if (maxBudgetUsd.isPresent() && maxBudgetUsd.get().signum() <= 0) {
      throw new IllegalArgumentException(
          "maxBudgetUsd must be positive when present, got " + maxBudgetUsd.get());
    }
    Objects.requireNonNull(maxWallClock, "maxWallClock must not be null");
    if (maxWallClock.isZero() || maxWallClock.isNegative()) {
      throw new IllegalArgumentException(
          "maxWallClock must be strictly positive, got " + maxWallClock);
    }
    Objects.requireNonNull(toolTimeoutDefault, "toolTimeoutDefault must not be null");
    if (toolTimeoutDefault.isZero() || toolTimeoutDefault.isNegative()) {
      throw new IllegalArgumentException(
          "toolTimeoutDefault must be strictly positive, got " + toolTimeoutDefault);
    }
    if (maxContextTokens <= 0) {
      throw new IllegalArgumentException(
          "maxContextTokens must be positive, got " + maxContextTokens);
    }
  }

  /**
   * Returns the shared default-limits singleton.
   *
   * @return the production defaults documented in the class Javadoc
   */
  public static SessionLimits defaults() {
    return DEFAULTS;
  }
}
