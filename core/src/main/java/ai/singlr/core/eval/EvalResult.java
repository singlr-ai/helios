/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.ArrayList;
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

  /**
   * Per-example scores in evaluation order, sized to {@link #perExample()}. The natural shape for
   * {@link ParetoFrontier#add} when an optimizer scores a candidate over the validation set.
   */
  public double[] perExampleScores() {
    var out = new double[perExample.size()];
    for (var i = 0; i < perExample.size(); i++) {
      out[i] = perExample.get(i).score();
    }
    return out;
  }

  /**
   * Per-example results re-shaped for direct use as {@link ReflectiveMutator#propose} input. The
   * {@code expected} and {@code actual} fields are erased to {@link Object} so heterogeneous
   * datasets compose without generics gymnastics. One entry per example, in evaluation order.
   */
  public List<TraceFeedback> feedback() {
    var out = new ArrayList<TraceFeedback>(perExample.size());
    for (var r : perExample) {
      out.add(
          new TraceFeedback(
              r.example().input(),
              r.example().expected(),
              r.actual(),
              r.score(),
              r.feedback(),
              r.trace()));
    }
    return List.copyOf(out);
  }
}
