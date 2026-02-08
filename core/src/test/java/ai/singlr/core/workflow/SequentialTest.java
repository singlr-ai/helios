/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SequentialTest {

  @Test
  void singleStepRunsAndReturns() {
    var step = Step.sequential("seq", Step.function("a", ctx -> StepResult.success("a", "done")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("done", result.content());
  }

  @Test
  void multipleStepsChainContext() {
    var step =
        Step.sequential(
            "seq",
            Step.function("a", ctx -> StepResult.success("a", "first")),
            Step.function("b", ctx -> StepResult.success("b", "second")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("second", result.content());
    assertEquals("b", result.name());
  }

  @Test
  void failFastOnFirstFailure() {
    var bExecuted = new boolean[] {false};
    var step =
        Step.sequential(
            "seq",
            Step.function("a", ctx -> StepResult.failure("a", "broken")),
            Step.function(
                "b",
                ctx -> {
                  bExecuted[0] = true;
                  return StepResult.success("b", "ok");
                }));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertEquals("broken", result.error());
    assertFalse(bExecuted[0]);
  }

  @Test
  void emptyStepsList() {
    var step = new Sequential("seq", List.of());

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("seq", result.name());
  }

  @Test
  void contextThreadingStepTwoSeesStepOneResult() {
    var step =
        Step.sequential(
            "seq",
            Step.function("a", ctx -> StepResult.success("a", "from-a")),
            Step.function(
                "b",
                ctx -> {
                  var aResult = ctx.previousResults().get("a");
                  return StepResult.success("b", "saw: " + aResult.content());
                }));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("saw: from-a", result.content());
  }
}
