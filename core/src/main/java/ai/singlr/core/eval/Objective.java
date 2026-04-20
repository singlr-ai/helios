/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.eval;

/**
 * Evaluates a candidate from the search space and returns a {@link Score}.
 *
 * <p>The autoresearch loop uses {@code Objective} to measure candidates proposed by the LLM.
 * Implementations are responsible for whatever concrete work the objective requires — running a
 * shell script, calling {@link Evaluator} against a dataset, timing a query, etc.
 *
 * <p>Implementations may throw any exception. The loop catches exceptions and records a crash entry
 * in the experiment log.
 *
 * @param <C> the candidate type
 */
@FunctionalInterface
public interface Objective<C> {

  /**
   * Evaluate the candidate.
   *
   * @param candidate the candidate to score
   * @return the resulting score
   * @throws Exception on any failure; surfaced as a crash in the log
   */
  Score evaluate(C candidate) throws Exception;
}
