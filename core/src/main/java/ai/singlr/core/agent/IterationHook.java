/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

/**
 * Programmatic control over agent completion. A hook is invoked by the agent after each iteration
 * in which the model wants to stop (i.e. the response has no tool calls) and after the built-in
 * {@code minIterations} and {@code requiredTools} guardrails have been satisfied. The hook decides
 * whether the agent may complete, must stop immediately, or should loop again with an injected
 * guidance message.
 *
 * <p>The hook never fires mid-tool-use. It always respects {@code maxIterations} as an absolute
 * ceiling — an {@link IterationAction.Inject} returned on the final allowed iteration will loop
 * once more, hit the ceiling, and the agent will fail with the existing "Max iterations reached"
 * error.
 *
 * <p>Hooks must be deterministic and side-effect free. Exceptions thrown from the hook are caught
 * by the agent and surfaced as a failed run — they do not propagate to the caller.
 */
@FunctionalInterface
public interface IterationHook {

  /**
   * Called after an iteration in which the model wants to stop and the built-in guardrails have
   * been satisfied. The returned {@link IterationAction} controls what happens next.
   *
   * @param context immutable snapshot of the iteration state
   * @return the action to take; returning {@code null} is treated as {@link IterationAction.Allow}
   */
  IterationAction afterIteration(IterationContext context);
}
