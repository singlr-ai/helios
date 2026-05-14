/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.examples.gepa;

import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.eval.ParetoFrontier;
import java.time.OffsetDateTime;

/**
 * Result of a {@link GepaPromptOptimizer#optimize()} run.
 *
 * <p>{@link #bestPrompt} is the single best candidate by aggregate validation score — the
 * load-bearing default for callers that want one prompt to ship. The full {@link #frontier} is
 * exposed so callers with weighted validation instances can re-pick by their own scoring rule.
 *
 * @param bestPrompt the prompt with the highest aggregate validation score
 * @param bestAggregateScore that prompt's aggregate (sum of per-example scores)
 * @param frontier the Pareto frontier at the end of optimization
 * @param lineage parent → child relationships for every candidate proposed
 * @param iterationsRun number of reflection iterations actually run
 * @param totalMetricCalls cumulative metric invocations (seed + minibatch + validation passes)
 * @param totalReflectionLmCalls cumulative reflection LM calls (always equal to {@code
 *     iterationsRun} unless the reflection mutator retried internally)
 * @param finishedAt UTC timestamp of the {@code optimize()} return
 */
public record GepaResult(
    String bestPrompt,
    double bestAggregateScore,
    ParetoFrontier<String> frontier,
    CandidateLineage lineage,
    int iterationsRun,
    int totalMetricCalls,
    int totalReflectionLmCalls,
    OffsetDateTime finishedAt) {

  /**
   * Returns an {@link AgentConfig} derived from {@code original} with the system prompt swapped to
   * {@link #bestPrompt}. The original config is not modified.
   */
  public AgentConfig applyTo(AgentConfig original) {
    if (original == null) {
      throw new IllegalArgumentException("original must not be null");
    }
    return AgentConfig.newBuilder(original).withSystemPrompt(bestPrompt).build();
  }
}
