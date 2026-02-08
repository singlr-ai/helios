/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

/**
 * A step that branches on a predicate. If the predicate is true, runs {@code ifStep}; otherwise
 * runs {@code elseStep} (if present) or returns a skip result.
 *
 * @param name the step name
 * @param predicate the condition to evaluate
 * @param ifStep the step to run when the predicate is true
 * @param elseStep the step to run when the predicate is false, or null to skip
 */
public record Condition(String name, StepPredicate predicate, Step ifStep, Step elseStep)
    implements Step {

  /** Creates a condition with no else branch. */
  public Condition(String name, StepPredicate predicate, Step ifStep) {
    this(name, predicate, ifStep, null);
  }

  @Override
  public StepResult execute(StepContext context) {
    boolean result;
    try {
      result = predicate.test(context);
    } catch (Exception e) {
      return StepResult.failure(name, e.getMessage());
    }
    if (result) {
      return ifStep.execute(context);
    }
    if (elseStep != null) {
      return elseStep.execute(context);
    }
    return StepResult.skip(name);
  }
}
