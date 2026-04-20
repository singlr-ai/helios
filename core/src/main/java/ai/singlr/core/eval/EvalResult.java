/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.List;

/**
 * Aggregated output of {@link Evaluator#run()}.
 *
 * @param meanScore arithmetic mean of per-example scores
 * @param perExample per-example results in the same order as the input dataset
 * @param <I> input type
 * @param <O> expected output type
 */
public record EvalResult<I, O>(double meanScore, List<ExampleResult<I, O>> perExample) {

  public EvalResult {
    perExample = perExample == null ? List.of() : List.copyOf(perExample);
  }
}
