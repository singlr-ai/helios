/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FallbackTest {

  @Test
  void firstStepSucceedsReturnsImmediately() {
    var secondExecuted = new boolean[] {false};
    var step =
        Step.fallback(
            "fb",
            Step.function("a", ctx -> StepResult.success("a", "primary")),
            Step.function(
                "b",
                ctx -> {
                  secondExecuted[0] = true;
                  return StepResult.success("b", "backup");
                }));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("primary", result.content());
    assertFalse(secondExecuted[0]);
  }

  @Test
  void firstFailsSecondSucceeds() {
    var step =
        Step.fallback(
            "fb",
            Step.function("a", ctx -> StepResult.failure("a", "primary failed")),
            Step.function("b", ctx -> StepResult.success("b", "backup worked")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("backup worked", result.content());
    assertEquals("b", result.name());
  }

  @Test
  void allFailReturnsFailure() {
    var step =
        Step.fallback(
            "fb",
            Step.function("a", ctx -> StepResult.failure("a", "a failed")),
            Step.function("b", ctx -> StepResult.failure("b", "b failed")));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertTrue(result.error().startsWith("All fallback steps failed"));
    assertTrue(result.error().contains("a: a failed"));
    assertTrue(result.error().contains("b: b failed"));
    assertEquals("fb", result.name());
  }

  @Test
  void singleStepDegenerateCase() {
    var step =
        Step.fallback("fb", Step.function("a", ctx -> StepResult.success("a", "only option")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("only option", result.content());
  }
}
