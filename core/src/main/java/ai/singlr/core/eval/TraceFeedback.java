/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import ai.singlr.core.common.Strings;
import ai.singlr.core.trace.Trace;

/**
 * A single (example, output, score, feedback) tuple from a candidate's evaluation, suitable for
 * passing to {@link ReflectiveMutator#propose}. Erases the example's typed input/expected to {@code
 * Object} so a mutator can consume traces from heterogeneous {@link Example} shapes without
 * fighting generics — implementations typically render them as text for an LLM prompt anyway.
 *
 * @param exampleInput the input the agent was given
 * @param exampleExpected the reference value the metric compared against (may be {@code null} for
 *     metrics that don't use a reference)
 * @param actualOutput the value the agent produced
 * @param score the metric score for this example
 * @param feedback model-readable feedback from the metric; never {@code null}, may be blank
 * @param trace the execution trace, or {@code null} if none was captured
 */
public record TraceFeedback(
    Object exampleInput,
    Object exampleExpected,
    Object actualOutput,
    double score,
    String feedback,
    Trace trace) {

  public TraceFeedback {
    feedback = Strings.isBlank(feedback) ? "" : feedback;
  }
}
