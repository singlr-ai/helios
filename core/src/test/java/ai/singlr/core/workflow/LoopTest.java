/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoopTest {

  @Test
  void loopRunsUntilPredicateFalse() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> counter.get() < 3,
            Step.function(
                "body",
                ctx -> {
                  var i = counter.incrementAndGet();
                  return StepResult.success(
                      "body", "iteration-" + i, Map.of("count", String.valueOf(i)));
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals(3, counter.get());
    assertEquals("iteration-3", result.content());
  }

  @Test
  void loopRespectsMaxIterations() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> true,
            Step.function(
                "body",
                ctx -> {
                  counter.incrementAndGet();
                  return StepResult.success("body", "ok");
                }),
            5);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals(5, counter.get());
  }

  @Test
  void loopStopsOnBodyFailure() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> true,
            Step.function(
                "body",
                ctx -> {
                  if (counter.incrementAndGet() == 2) {
                    return StepResult.failure("body", "failed on 2");
                  }
                  return StepResult.success("body", "ok");
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertEquals(2, counter.get());
    assertEquals("failed on 2", result.error());
  }

  @Test
  void predicateFalseOnFirstTestReturnsSkip() {
    var step =
        Step.loop(
            "loop",
            ctx -> false,
            Step.function("body", ctx -> StepResult.success("body", "should not run")),
            10);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertNull(result.content());
    assertEquals("loop", result.name());
  }

  @Test
  void contextAccumulatesThroughIterations() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> counter.get() < 3,
            Step.function(
                "body",
                ctx -> {
                  var i = counter.incrementAndGet();
                  var prevCount = ctx.previousResults().size();
                  return StepResult.success(
                      "body-" + i, "iter-" + i, Map.of("prevCount", String.valueOf(prevCount)));
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals(3, counter.get());
  }

  @Test
  void singleIterationLoop() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> counter.get() < 1,
            Step.function(
                "body",
                ctx -> {
                  counter.incrementAndGet();
                  return StepResult.success("body", "once");
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals(1, counter.get());
    assertEquals("once", result.content());
  }

  @Test
  void predicateCanReadLastResultFromBody() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> ctx.lastResult() == null || !ctx.lastResult().content().equals("stop"),
            Step.function(
                "body",
                ctx -> {
                  var i = counter.incrementAndGet();
                  return StepResult.success("body", i >= 3 ? "stop" : "continue");
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals(3, counter.get());
    assertEquals("stop", result.content());
  }

  @Test
  void maxIterationsValidation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Step.loop(
                "loop",
                ctx -> true,
                Step.function("body", ctx -> StepResult.success("body", "ok")),
                0));
  }

  @Test
  void predicateExceptionReturnsFailure() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "loop",
            ctx -> {
              if (counter.get() == 2) {
                throw new RuntimeException("predicate boom");
              }
              return true;
            },
            Step.function(
                "body",
                ctx -> {
                  counter.incrementAndGet();
                  return StepResult.success("body", "ok");
                }),
            10);

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertEquals("predicate boom", result.error());
    assertEquals(2, counter.get());
  }
}
