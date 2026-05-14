/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import java.util.List;

/**
 * Proposes a new candidate based on a parent candidate and feedback from its evaluation.
 *
 * <p>GEPA-style optimizers run reflective mutation instead of random mutation: instead of
 * perturbing the parent blindly, an implementation looks at how the parent performed on a sample of
 * validation traces (input, expected, actual, score, feedback) and proposes a revised candidate
 * that addresses observed failures. The reflection step itself is usually an LLM call ({@link
 * LlmReflectiveMutator} is the reference impl for {@code C = String}); other implementations could
 * do genetic crossover, temperature sweeps, or rule-based edits.
 *
 * <p>Functional interface — keep it cheap to swap implementations during experimentation.
 *
 * @param <C> candidate type (e.g. {@code String} prompt, {@code AgentConfig}, custom record)
 */
@FunctionalInterface
public interface ReflectiveMutator<C> {

  /**
   * Propose a new candidate derived from {@code parent} given the feedback traces.
   *
   * @param parent the parent candidate
   * @param traces a sample of evaluation traces from the parent's last evaluation; the
   *     implementation is free to consume all or a subset
   * @return the proposed new candidate
   */
  C propose(C parent, List<TraceFeedback> traces);
}
