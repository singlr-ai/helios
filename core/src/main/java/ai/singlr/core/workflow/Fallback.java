/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * A step that tries child steps in order until one succeeds. Returns the first successful result,
 * or a failure with aggregated error details if all steps fail.
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
    var errors = new ArrayList<String>(steps.size());
    for (var step : steps) {
      var result = step.execute(context);
      if (result.success()) {
        return result;
      }
      errors.add(step.name() + ": " + result.error());
    }
    return StepResult.failure(
        name, "All fallback steps failed [" + String.join("; ", errors) + "]");
  }
}
