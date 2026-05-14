/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A non-negative USD cost estimate accumulated across a session's model calls and tool invocations.
 *
 * <p>{@code BigDecimal} is the underlying type to avoid floating-point drift when summing many
 * per-call costs. Use {@link #plus(CostEstimate)} to accumulate; the result is a fresh immutable
 * instance.
 *
 * @param usd the cost in US dollars; non-null and non-negative
 */
public record CostEstimate(BigDecimal usd) {

  private static final CostEstimate ZERO = new CostEstimate(BigDecimal.ZERO);

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code usd} is null
   * @throws IllegalArgumentException if {@code usd} is negative
   */
  public CostEstimate {
    Objects.requireNonNull(usd, "usd must not be null");
    if (usd.signum() < 0) {
      throw new IllegalArgumentException("usd must be non-negative, got " + usd);
    }
  }

  /**
   * Returns the shared zero-cost singleton.
   *
   * @return a {@code CostEstimate} with {@code usd == 0}
   */
  public static CostEstimate zero() {
    return ZERO;
  }

  /**
   * Build a {@code CostEstimate} from a {@code double} dollar amount. Convenience for tests and
   * scripts; production code should prefer the canonical constructor with {@code BigDecimal}.
   *
   * @param amount the dollar amount; must be non-negative
   * @return a new {@code CostEstimate}
   */
  public static CostEstimate ofUsd(double amount) {
    return new CostEstimate(BigDecimal.valueOf(amount));
  }

  /**
   * Sum this cost with another and return a fresh {@code CostEstimate}.
   *
   * @param other the cost to add; non-null
   * @return a new {@code CostEstimate} whose {@code usd} is {@code this.usd.add(other.usd)}
   * @throws NullPointerException if {@code other} is null
   */
  public CostEstimate plus(CostEstimate other) {
    Objects.requireNonNull(other, "other must not be null");
    return new CostEstimate(this.usd.add(other.usd));
  }
}
