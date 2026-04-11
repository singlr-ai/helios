/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Response;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of agent state passed to {@link
 * IterationHook#afterIteration(IterationContext)} after each iteration in which the model wants to
 * stop.
 *
 * @param iteration 1-based index of the iteration that just completed
 * @param maxIterations the configured absolute ceiling
 * @param minIterations the configured floor (0 if not set)
 * @param requiredTools the configured required tools (empty if not set)
 * @param toolsCalledSoFar names of tools that have been called at least once across the
 *     conversation up to this point
 * @param totalToolCallCount the total number of tool calls made across the conversation (multiple
 *     calls to the same tool each count)
 * @param lastResponse the response returned by the model on this iteration
 * @param messages an immutable snapshot of the full message history, including the assistant
 *     response that triggered this hook invocation
 */
public record IterationContext(
    int iteration,
    int maxIterations,
    int minIterations,
    Set<String> requiredTools,
    Set<String> toolsCalledSoFar,
    int totalToolCallCount,
    Response<?> lastResponse,
    List<Message> messages) {

  public IterationContext {
    requiredTools = Set.copyOf(requiredTools);
    toolsCalledSoFar = Set.copyOf(toolsCalledSoFar);
    messages = List.copyOf(messages);
  }
}
