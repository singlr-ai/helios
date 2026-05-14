/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

/**
 * Pre-set optimizer budgets that mirror DSPy GEPA's {@code auto='light'|'medium'|'heavy'} dial.
 * Pick one when you want sensible defaults; pass {@link
 * GepaPromptOptimizer.Builder#maxIterations(int)} when you want explicit control.
 *
 * <p>The budget is parameterised by the validation set size and the number of predictors being
 * optimized — for {@code helios-gepa-prompt} there is always exactly one predictor (the student
 * agent's system prompt). The formulas below are the calibration DSPy ships; we keep the same
 * numbers so users migrating from DSPy GEPA get comparable behaviour without a re-tuning pass.
 */
public enum AutoBudget {

  /** Quick smoke-test budget — useful for "does my pipeline even work?" iterations. */
  LIGHT(6),

  /** The default GEPA budget. Strong tradeoff between cost and quality. */
  MEDIUM(12),

  /** Long-running budget; expect 2x the wall-clock and cost of {@link #MEDIUM}. */
  HEAVY(24);

  private final int baseIterations;

  AutoBudget(int baseIterations) {
    this.baseIterations = baseIterations;
  }

  /**
   * Max optimizer iterations. Each iteration runs one minibatch evaluation on the parent + one full
   * validation pass on the child.
   *
   * @param valSetSize size of the validation set
   * @param numPredictors how many distinct prompts this optimizer is tuning (currently always 1)
   * @return iteration cap; minimum {@link #baseIterations}
   */
  public int maxIterations(int valSetSize, int numPredictors) {
    if (valSetSize < 1) {
      throw new IllegalArgumentException("valSetSize must be >= 1");
    }
    if (numPredictors < 1) {
      throw new IllegalArgumentException("numPredictors must be >= 1");
    }
    return baseIterations * numPredictors;
  }

  /**
   * Soft cap on total metric calls (sum of seed + per-iteration val passes + per-iteration
   * minibatch passes). The optimizer stops when either {@link #maxIterations} or this cap is
   * reached, whichever comes first.
   *
   * @param valSetSize size of the validation set
   * @param numPredictors how many distinct prompts this optimizer is tuning
   * @return metric-call cap
   */
  public int maxMetricCalls(int valSetSize, int numPredictors) {
    if (valSetSize < 1) {
      throw new IllegalArgumentException("valSetSize must be >= 1");
    }
    if (numPredictors < 1) {
      throw new IllegalArgumentException("numPredictors must be >= 1");
    }
    // val pass + minibatch pass per iteration. Minibatch is sized off the optimizer's config,
    // not the budget — we model the upper bound assuming the minibatch equals the val set.
    return valSetSize * baseIterations * 2 * numPredictors;
  }
}
