/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decides which {@link TraceFeedback} entries the reflection LM gets to see. The default
 * implementation ({@link #failuresFirst}) keeps every failure (score below threshold) and adds a
 * sample of successes up to a budget — biases reflection toward what went wrong without losing all
 * signal about what already works.
 */
@FunctionalInterface
public interface TraceSampler {

  /**
   * Select a subset of the provided traces, in the order the reflection LM should see them.
   *
   * @param traces all traces from the parent's evaluation, in evaluation order
   * @return the sampled subset
   */
  List<TraceFeedback> sample(List<TraceFeedback> traces);

  /**
   * Default sampler: every trace with {@code score < failureThreshold} (in evaluation order),
   * followed by up to {@code maxSuccesses} randomly-chosen successes. Common GEPA setting:
   * threshold = 1.0 (keep every non-perfect run) with {@code maxSuccesses = 2}.
   */
  static TraceSampler failuresFirst(double failureThreshold, int maxSuccesses, Random rng) {
    if (rng == null) {
      throw new IllegalArgumentException("rng must not be null");
    }
    if (maxSuccesses < 0) {
      throw new IllegalArgumentException("maxSuccesses must be >= 0");
    }
    return traces -> {
      var failures = new ArrayList<TraceFeedback>();
      var successes = new ArrayList<TraceFeedback>();
      for (var t : traces) {
        if (t.score() < failureThreshold) {
          failures.add(t);
        } else {
          successes.add(t);
        }
      }
      var sampled = new ArrayList<TraceFeedback>(failures);
      var pickable = new ArrayList<>(successes);
      var picks = Math.min(maxSuccesses, pickable.size());
      for (var i = 0; i < picks; i++) {
        var idx = rng.nextInt(pickable.size());
        sampled.add(pickable.remove(idx));
      }
      return List.copyOf(sampled);
    };
  }
}
