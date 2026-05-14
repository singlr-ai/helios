/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import ai.singlr.core.common.Strings;
import ai.singlr.core.trace.Trace;

/**
 * Scores an actual output against an expected reference AND returns model-readable feedback
 * explaining the score. Sibling to {@link Metric}; coexists, not replaces.
 *
 * <p>GEPA-style reflective optimizers need feedback alongside the scalar — the reflection LM is far
 * more effective when told <em>why</em> a candidate scored what it did than when given the score
 * alone. Scalar metrics stay perfectly valid for callers that don't need feedback (e.g. final
 * leaderboard evaluation).
 *
 * <p>Any {@code FeedbackMetric} adapts to a scalar {@link Metric} via {@link #asScalar()} — useful
 * when passing the same scorer to APIs that only accept {@code Metric}.
 *
 * @param <E> the expected/reference type
 * @param <A> the actual/produced type
 */
@FunctionalInterface
public interface FeedbackMetric<E, A> {

  /**
   * Compute a {@link Result} carrying both the numeric score and a feedback string.
   *
   * @param expected the reference value
   * @param actual the value produced by the agent or objective
   * @param trace the execution trace, or {@code null} if no trace is available
   * @return the score and feedback; feedback may be blank when the metric has nothing additional to
   *     say
   */
  Result score(E expected, A actual, Trace trace);

  /** Adapt to scalar {@link Metric}. Feedback is dropped on the way through. */
  default Metric<E, A> asScalar() {
    return (e, a, t) -> score(e, a, t).score();
  }

  /**
   * The output of a {@code FeedbackMetric} call.
   *
   * @param score numeric score; higher or lower meaning is defined by the metric
   * @param feedback model-readable explanation of the score (never {@code null}; may be blank)
   */
  record Result(double score, String feedback) {
    public Result {
      feedback = Strings.isBlank(feedback) ? "" : feedback;
    }

    /** Convenience factory equivalent to the canonical constructor. */
    public static Result of(double score, String feedback) {
      return new Result(score, feedback);
    }

    /** Convenience factory when no feedback is available. */
    public static Result scoreOnly(double score) {
      return new Result(score, "");
    }
  }
}
