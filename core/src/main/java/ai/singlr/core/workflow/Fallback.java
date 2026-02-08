/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import java.util.List;

/**
 * A step that tries child steps in order until one succeeds. Returns the first successful result,
 * or a failure if all steps fail.
 *
 * @param name the step name
 * @param steps the steps to try in order
 */
public record Fallback(String name, List<Step> steps) implements Step {

  public Fallback {
    steps = List.copyOf(steps);
  }

  @Override
  public StepResult execute(StepContext context) {
    for (var step : steps) {
      var result = step.execute(context);
      if (result.success()) {
        return result;
      }
    }
    return StepResult.failure(name, "All fallback steps failed");
  }
}
