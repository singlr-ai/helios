/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Response;
import ai.singlr.core.trace.Trace;

/**
 * Per-example result produced by {@link Evaluator}.
 *
 * @param example the example that was evaluated
 * @param actual the actual output produced by the agent, or {@code null} if the run failed
 * @param score metric score for this example; typically {@code 0.0} on failure unless the metric
 *     chooses otherwise
 * @param feedback model-readable feedback from the metric; never {@code null}, blank when the
 *     evaluator was driven with a scalar {@link Metric} that has nothing to add
 * @param trace the execution trace, or {@code null} if tracing was not attached
 * @param outcome the underlying {@link Result} from the agent run
 * @param <I> input type
 * @param <O> expected output type
 */
public record ExampleResult<I, O>(
    Example<I, O> example,
    O actual,
    double score,
    String feedback,
    Trace trace,
    Result<Response<O>> outcome) {

  public ExampleResult {
    feedback = Strings.isBlank(feedback) ? "" : feedback;
  }

  /** Backwards-compatible constructor used by the scalar {@link Metric} path. */
  public ExampleResult(
      Example<I, O> example, O actual, double score, Trace trace, Result<Response<O>> outcome) {
    this(example, actual, score, "", trace, outcome);
  }
}
