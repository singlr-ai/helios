/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import ai.singlr.core.trace.Trace;

/**
 * Scores how well an actual output matches an expected output.
 *
 * <p>Used by {@link Evaluator} to compute per-example scores when running an agent over a labeled
 * dataset, and by user-supplied {@link Objective} implementations that compare produced output to a
 * reference.
 *
 * <p>Implementations may inspect the execution {@link Trace} to weight scores with runtime signals
 * (tokens used, tool calls, latency). The {@code trace} argument may be {@code null} when no trace
 * was captured — implementations must handle that case.
 *
 * @param <T> the output type being scored
 */
@FunctionalInterface
public interface Metric<T> {

  /**
   * Compute a score for {@code actual} against {@code expected}.
   *
   * @param expected the reference output
   * @param actual the output produced by the agent or objective
   * @param trace the execution trace, or {@code null} if no trace is available
   * @return a numeric score; higher or lower meaning is defined by the metric
   */
  double score(T expected, T actual, Trace trace);

  /**
   * Returns a metric that scores 1.0 for equal values and 0.0 otherwise, ignoring the trace.
   *
   * @param <T> the output type
   * @return an exact-match metric
   */
  static <T> Metric<T> exactMatch() {
    return (expected, actual, trace) -> {
      if (expected == null) {
        return actual == null ? 1.0 : 0.0;
      }
      return expected.equals(actual) ? 1.0 : 0.0;
    };
  }
}
