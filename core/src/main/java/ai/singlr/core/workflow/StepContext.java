/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import ai.singlr.core.agent.SessionContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable context flowing through workflow steps. Each step receives the accumulated context from
 * all prior steps and produces a new context via {@link #withResult(StepResult)}.
 *
 * @param input the original workflow input
 * @param previousResults all prior step results keyed by step name
 * @param lastResult the most recent step result, or null if no steps have run
 * @param session the session context for agent steps, or null for stateless workflows
 */
public record StepContext(
    String input,
    Map<String, StepResult> previousResults,
    StepResult lastResult,
    SessionContext session) {

  /** Creates an initial context with the given input and no prior results. */
  public static StepContext of(String input) {
    return new StepContext(input, Map.of(), null, null);
  }

  /** Creates an initial context with the given input and session. */
  public static StepContext of(String input, SessionContext session) {
    return new StepContext(input, Map.of(), null, session);
  }

  /** Returns a new context with the given result appended to previous results. */
  public StepContext withResult(StepResult result) {
    var updated = new LinkedHashMap<>(previousResults);
    updated.put(result.name(), result);
    return new StepContext(input, Map.copyOf(updated), result, session);
  }
}
