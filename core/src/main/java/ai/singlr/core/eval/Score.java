/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.Map;

/**
 * The outcome of scoring a candidate — a primary scalar plus optional tradeoff metrics and
 * free-form diagnostics.
 *
 * <p>Used by {@link Objective} to report results back to the autoresearch loop. The {@code value}
 * is the optimization target; {@code secondary} holds tradeoff monitors (latency, token cost, size)
 * that are logged but do not normally drive keep/discard decisions; {@code diagnostics} holds
 * arbitrary context the objective wants to persist to the experiment log.
 *
 * @param value primary metric
 * @param secondary tradeoff metrics keyed by name
 * @param diagnostics arbitrary diagnostic payload
 */
public record Score(double value, Map<String, Double> secondary, Map<String, Object> diagnostics) {

  public Score {
    secondary = secondary == null ? Map.of() : Map.copyOf(secondary);
    diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
  }

  /**
   * Create a score with only a primary value.
   *
   * @param value primary metric
   * @return score with empty secondary and diagnostics maps
   */
  public static Score of(double value) {
    return new Score(value, Map.of(), Map.of());
  }

  /**
   * Create a score with a primary value and secondary metrics.
   *
   * @param value primary metric
   * @param secondary tradeoff metrics
   * @return score with empty diagnostics
   */
  public static Score of(double value, Map<String, Double> secondary) {
    return new Score(value, secondary, Map.of());
  }
}
