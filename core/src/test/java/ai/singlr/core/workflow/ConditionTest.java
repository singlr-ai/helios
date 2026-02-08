/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConditionTest {

  @Test
  void predicateTrueRunsIfStep() {
    var step =
        Step.condition(
            "cond",
            ctx -> true,
            Step.function("yes", ctx -> StepResult.success("yes", "if-branch")),
            Step.function("no", ctx -> StepResult.success("no", "else-branch")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("if-branch", result.content());
    assertEquals("yes", result.name());
  }

  @Test
  void predicateFalseWithElseStepRunsElse() {
    var step =
        Step.condition(
            "cond",
            ctx -> false,
            Step.function("yes", ctx -> StepResult.success("yes", "if-branch")),
            Step.function("no", ctx -> StepResult.success("no", "else-branch")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("else-branch", result.content());
    assertEquals("no", result.name());
  }

  @Test
  void predicateFalseNoElseStepReturnsSkip() {
    var step =
        Step.condition(
            "cond", ctx -> false, Step.function("yes", ctx -> StepResult.success("yes", "taken")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertNull(result.content());
    assertEquals("cond", result.name());
  }

  @Test
  void predicateBasedOnLastResultContent() {
    var ctx =
        StepContext.of("input").withResult(StepResult.success("prev", "contains-urgent-flag"));

    var step =
        Step.condition(
            "route",
            c -> c.lastResult() != null && c.lastResult().content().contains("urgent"),
            Step.function("urgent", c -> StepResult.success("urgent", "handled urgently")),
            Step.function("normal", c -> StepResult.success("normal", "handled normally")));

    var result = step.execute(ctx);

    assertTrue(result.success());
    assertEquals("handled urgently", result.content());
  }

  @Test
  void predicateExceptionReturnsFailure() {
    var step =
        Step.condition(
            "cond",
            ctx -> {
              throw new RuntimeException("predicate boom");
            },
            Step.function("yes", ctx -> StepResult.success("yes", "should not run")));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertEquals("predicate boom", result.error());
    assertEquals("cond", result.name());
  }
}
