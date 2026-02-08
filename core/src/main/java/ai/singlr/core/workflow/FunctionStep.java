/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

/**
 * A step that executes an arbitrary function.
 *
 * @param name the step name
 * @param function the function to execute
 */
public record FunctionStep(String name, StepFunction function) implements Step {

  @Override
  public StepResult execute(StepContext context) {
    try {
      return function.apply(context);
    } catch (Exception e) {
      return StepResult.failure(name, e.getMessage());
    }
  }
}
