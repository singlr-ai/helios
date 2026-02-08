/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompositionTest {

  @Test
  void sequentialInsideParallel() {
    var step =
        Step.parallel(
            "par",
            Step.sequential(
                "seq-a",
                Step.function("a1", ctx -> StepResult.success("a1", "first")),
                Step.function(
                    "a2", ctx -> StepResult.success("a2", "saw: " + ctx.lastResult().content()))),
            Step.sequential(
                "seq-b",
                Step.function("b1", ctx -> StepResult.success("b1", "alpha")),
                Step.function(
                    "b2", ctx -> StepResult.success("b2", "saw: " + ctx.lastResult().content()))));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertTrue(result.content().contains("saw: first"));
    assertTrue(result.content().contains("saw: alpha"));
  }

  @Test
  void parallelInsideSequential() {
    var step =
        Step.sequential(
            "seq",
            Step.parallel(
                "par",
                Step.function("a", ctx -> StepResult.success("a", "one", Map.of("k", "v"))),
                Step.function("b", ctx -> StepResult.success("b", "two"))),
            Step.function(
                "after",
                ctx -> {
                  var prevData = ctx.previousResults().get("par").data();
                  return StepResult.success("after", "data-k=" + prevData.get("k"));
                }));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("data-k=v", result.content());
  }

  @Test
  void conditionInsideSequential() {
    var step =
        Step.sequential(
            "pipeline",
            Step.function("classify", ctx -> StepResult.success("classify", "urgent")),
            Step.condition(
                "route",
                ctx -> ctx.lastResult().content().contains("urgent"),
                Step.function(
                    "urgent-handler", ctx -> StepResult.success("urgent-handler", "escalated")),
                Step.function(
                    "normal-handler", ctx -> StepResult.success("normal-handler", "queued"))));

    var result = step.execute(StepContext.of("help me"));

    assertTrue(result.success());
    assertEquals("escalated", result.content());
  }

  @Test
  void conditionElseBranchInsideSequential() {
    var step =
        Step.sequential(
            "pipeline",
            Step.function("classify", ctx -> StepResult.success("classify", "low-priority")),
            Step.condition(
                "route",
                ctx -> ctx.lastResult().content().contains("urgent"),
                Step.function(
                    "urgent-handler", ctx -> StepResult.success("urgent-handler", "escalated")),
                Step.function(
                    "normal-handler", ctx -> StepResult.success("normal-handler", "queued"))));

    var result = step.execute(StepContext.of("just asking"));

    assertTrue(result.success());
    assertEquals("queued", result.content());
  }

  @Test
  void loopInsideSequentialWithDataAccumulation() {
    var counter = new AtomicInteger(0);

    var step =
        Step.sequential(
            "pipeline",
            Step.function("init", ctx -> StepResult.success("init", "starting")),
            Step.loop(
                "retry-loop",
                ctx -> counter.get() < 3,
                Step.function(
                    "attempt",
                    ctx -> {
                      var i = counter.incrementAndGet();
                      return StepResult.success("attempt", "try-" + i);
                    }),
                10),
            Step.function(
                "summarize",
                ctx -> StepResult.success("summarize", "done after " + counter.get())));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("done after 3", result.content());
  }

  @Test
  void fallbackWithNestedSequentials() {
    var step =
        Step.fallback(
            "fb",
            Step.sequential(
                "primary",
                Step.function("p1", ctx -> StepResult.success("p1", "ok")),
                Step.function("p2", ctx -> StepResult.failure("p2", "primary broke"))),
            Step.sequential(
                "backup",
                Step.function("b1", ctx -> StepResult.success("b1", "backup")),
                Step.function("b2", ctx -> StepResult.success("b2", "recovered"))));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("recovered", result.content());
  }

  @Test
  void deeplyNestedThreeLevels() {
    var step =
        Step.sequential(
            "outer",
            Step.parallel(
                "middle",
                Step.sequential(
                    "inner-a", Step.function("a1", ctx -> StepResult.success("a1", "deep-a"))),
                Step.sequential(
                    "inner-b", Step.function("b1", ctx -> StepResult.success("b1", "deep-b")))),
            Step.function("final", ctx -> StepResult.success("final", "complete")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("complete", result.content());
  }

  @Test
  void failureInNestedStepPropagatesToOuter() {
    var step =
        Step.sequential(
            "outer",
            Step.parallel(
                "par",
                Step.function("ok", ctx -> StepResult.success("ok", "fine")),
                Step.sequential(
                    "inner-seq",
                    Step.function("fail", ctx -> StepResult.failure("fail", "inner broke")))));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertTrue(result.error().contains("inner broke"));
  }

  @Test
  void conditionWithParallelBranches() {
    var step =
        Step.condition(
            "branch",
            ctx -> true,
            Step.parallel(
                "par-if",
                Step.function("a", ctx -> StepResult.success("a", "par-a")),
                Step.function("b", ctx -> StepResult.success("b", "par-b"))));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertTrue(result.content().contains("par-a"));
    assertTrue(result.content().contains("par-b"));
  }

  @Test
  void loopWithFallbackBody() {
    var counter = new AtomicInteger(0);

    var step =
        Step.loop(
            "retry",
            ctx -> counter.get() < 3,
            Step.fallback(
                "try-sources",
                Step.function(
                    "primary",
                    ctx -> {
                      if (counter.get() < 2) {
                        return StepResult.failure("primary", "unavailable");
                      }
                      return StepResult.success("primary", "got-it");
                    }),
                Step.function(
                    "backup",
                    ctx -> {
                      counter.incrementAndGet();
                      return StepResult.success("backup", "fallback-" + counter.get());
                    })),
            5);

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("got-it", result.content());
  }
}
