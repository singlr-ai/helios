/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FunctionStepTest {

  @Test
  void functionReturnsSuccessResult() {
    var step = Step.function("greet", ctx -> StepResult.success("greet", "Hello, " + ctx.input()));

    var result = step.execute(StepContext.of("world"));

    assertTrue(result.success());
    assertEquals("Hello, world", result.content());
  }

  @Test
  void functionReturnsFailureResult() {
    var step = Step.function("fail", ctx -> StepResult.failure("fail", "bad input"));

    var result = step.execute(StepContext.of("test"));

    assertFalse(result.success());
    assertEquals("bad input", result.error());
  }

  @Test
  void functionThrowsExceptionWrappedAsFailure() {
    var step =
        Step.function(
            "throw",
            ctx -> {
              throw new RuntimeException("boom");
            });

    var result = step.execute(StepContext.of("test"));

    assertFalse(result.success());
    assertEquals("boom", result.error());
    assertEquals("throw", result.name());
  }

  @Test
  void functionAccessesPreviousResults() {
    var ctx = StepContext.of("input").withResult(StepResult.success("prev", "earlier-output"));
    var step =
        Step.function(
            "next",
            c -> {
              var prev = c.previousResults().get("prev");
              return StepResult.success("next", "got: " + prev.content());
            });

    var result = step.execute(ctx);

    assertTrue(result.success());
    assertEquals("got: earlier-output", result.content());
  }

  @Test
  void functionNameAccessor() {
    var step = Step.function("myFunc", ctx -> StepResult.success("myFunc", "ok"));
    assertEquals("myFunc", step.name());
  }
}
