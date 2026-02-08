/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

/**
 * A step that repeats a body step while a condition holds. Uses while-loop semantics: the predicate
 * is tested before each iteration. Includes a max iterations guard to prevent infinite loops.
 *
 * @param name the step name
 * @param condition the predicate tested before each iteration
 * @param body the step to repeat
 * @param maxIterations the maximum number of iterations
 */
public record Loop(String name, StepPredicate condition, Step body, int maxIterations)
    implements Step {

  public Loop {
    if (maxIterations < 1) {
      throw new IllegalArgumentException("maxIterations must be >= 1");
    }
  }

  @Override
  public StepResult execute(StepContext context) {
    var current = context;
    StepResult lastResult = StepResult.skip(name);
    int count = 0;
    while (count < maxIterations) {
      try {
        if (!condition.test(current)) {
          break;
        }
      } catch (Exception e) {
        return StepResult.failure(name, e.getMessage());
      }
      lastResult = body.execute(current);
      current = current.withResult(lastResult);
      if (!lastResult.success()) {
        break;
      }
      count++;
    }
    return lastResult;
  }
}
