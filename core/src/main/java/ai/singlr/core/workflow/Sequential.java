/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import java.util.List;

/**
 * A step that runs child steps in order. Fail-fast: stops on the first unsuccessful result.
 *
 * @param name the step name
 * @param steps the steps to run in order
 */
public record Sequential(String name, List<Step> steps) implements Step {

  public Sequential {
    steps = List.copyOf(steps);
  }

  @Override
  public StepResult execute(StepContext context) {
    var current = context;
    StepResult lastResult = StepResult.skip(name);
    for (var step : steps) {
      lastResult = step.execute(current);
      current = current.withResult(lastResult);
      if (!lastResult.success()) {
        break;
      }
    }
    return lastResult;
  }
}
