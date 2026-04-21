/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import ai.singlr.core.trace.Trace;

/**
 * Scores how well an actual output matches an expected reference.
 *
 * <p>The expected and actual types are independent on purpose. Most similarity-style metrics (exact
 * match, string distance, semantic similarity) use the same type for both — e.g., {@code
 * Metric<String, String>}. But some metrics compare a criteria descriptor against a produced
 * output, or a structural shape against a trace — e.g., {@code Metric<ExpectedShape,
 * IntelligenceReport>} in depth-evaluation loops. Keeping the types separate means callers never
 * have to force the shape into a single record or pass it through a side channel.
 *
 * <p>Used by {@link Evaluator} (with {@code E == A} since it compares the agent's output to the
 * example's expected output) and by user-written runners that have different expected and actual
 * shapes.
 *
 * <p>Implementations may inspect the execution {@link Trace} to weight scores with runtime signals
 * (tokens used, tool calls, latency). The {@code trace} argument may be {@code null} when no trace
 * was captured — implementations must handle that case.
 *
 * @param <E> the expected/reference type
 * @param <A> the actual/produced type
 */
@FunctionalInterface
public interface Metric<E, A> {

  /**
   * Compute a score for {@code actual} against {@code expected}.
   *
   * @param expected the reference value (dataset label, criteria descriptor, target shape)
   * @param actual the value produced by the agent or objective
   * @param trace the execution trace, or {@code null} if no trace is available
   * @return a numeric score; higher or lower meaning is defined by the metric
   */
  double score(E expected, A actual, Trace trace);

  /**
   * Returns a metric that scores 1.0 for equal values and 0.0 otherwise, ignoring the trace. Both
   * type parameters collapse to a single {@code T} because exact match only makes sense when
   * expected and actual share a type.
   *
   * @param <T> the value type
   * @return an exact-match metric
   */
  static <T> Metric<T, T> exactMatch() {
    return (expected, actual, trace) -> {
      if (expected == null) {
        return actual == null ? 1.0 : 0.0;
      }
      return expected.equals(actual) ? 1.0 : 0.0;
    };
  }
}
