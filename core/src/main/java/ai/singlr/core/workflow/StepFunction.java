/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

/** A function that produces a {@link StepResult} from a {@link StepContext}. */
@FunctionalInterface
public interface StepFunction {

  /**
   * Applies this function to the given context.
   *
   * @param context the step context
   * @return the step result
   */
  StepResult apply(StepContext context);
}
