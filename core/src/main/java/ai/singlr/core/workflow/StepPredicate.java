/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

/** A predicate evaluated against a {@link StepContext} for conditions and loops. */
@FunctionalInterface
public interface StepPredicate {

  /**
   * Tests the given context.
   *
   * @param context the step context
   * @return true if the predicate holds
   */
  boolean test(StepContext context);
}
