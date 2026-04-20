/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

import java.util.Arrays;
import java.util.List;

/**
 * Computes a confidence score for an {@link ExperimentLog} segment using the Median Absolute
 * Deviation (MAD) as a robust noise floor.
 *
 * <p>The score answers the question "is the best improvement large enough to be real, given how
 * noisy this session is?" It is defined as {@code |best_improvement| / MAD}, where {@code
 * best_improvement = baseline - best_metric} (lower-is-better) and {@code MAD} is the median of
 * absolute deviations from the median metric across all entries in the segment.
 *
 * <p>The result is advisory: loop authors typically display it to the agent and let the agent
 * decide whether to re-run for confirmation. A score of {@code 2.0} means the improvement is twice
 * the noise floor; values below {@code 1.0} are within noise.
 *
 * <p>Returns {@code null} when there are fewer than three entries (not enough data), when all
 * values are identical (MAD is zero), or when called on an empty segment.
 */
public final class ConfidenceScorer {

  private static final int MIN_ENTRIES = 3;

  private ConfidenceScorer() {}

  /**
   * Score the current segment of {@code log}.
   *
   * @param log the experiment log
   * @return confidence score, or {@code null} when insufficient data
   */
  public static Double score(ExperimentLog log) {
    return score(log.segment(log.currentSegment()));
  }

  /**
   * Score the given entries. All entries are assumed to be from the same segment.
   *
   * @param entries entries to score
   * @return confidence score, or {@code null} when insufficient data
   */
  public static Double score(List<ExperimentEntry> entries) {
    if (entries == null || entries.size() < MIN_ENTRIES) {
      return null;
    }
    var values = new double[entries.size()];
    for (int i = 0; i < entries.size(); i++) {
      values[i] = entries.get(i).primaryMetric();
    }
    double baseline = values[0];
    double best = baseline;
    for (double v : values) {
      if (v < best) {
        best = v;
      }
    }
    double improvement = baseline - best;
    double mad = medianAbsoluteDeviation(values);
    if (mad == 0.0) {
      return null;
    }
    return Math.abs(improvement) / mad;
  }

  private static double medianAbsoluteDeviation(double[] values) {
    double median = median(values);
    var deviations = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      deviations[i] = Math.abs(values[i] - median);
    }
    return median(deviations);
  }

  private static double median(double[] input) {
    var sorted = Arrays.copyOf(input, input.length);
    Arrays.sort(sorted);
    int n = sorted.length;
    if ((n & 1) == 1) {
      return sorted[n / 2];
    }
    return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
  }
}
