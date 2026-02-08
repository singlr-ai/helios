/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ParallelTest {

  @Test
  void allStepsSucceedMergedResult() {
    var step =
        Step.parallel(
            "par",
            Step.function("a", ctx -> StepResult.success("a", "alpha", Map.of("k1", "v1"))),
            Step.function("b", ctx -> StepResult.success("b", "beta", Map.of("k2", "v2"))));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("par", result.name());
    assertTrue(result.content().contains("alpha"));
    assertTrue(result.content().contains("beta"));
    assertEquals("v1", result.data().get("k1"));
    assertEquals("v2", result.data().get("k2"));
  }

  @Test
  void oneStepFailsReturnsFailure() {
    var step =
        Step.parallel(
            "par",
            Step.function("a", ctx -> StepResult.success("a", "ok")),
            Step.function("b", ctx -> StepResult.failure("b", "broken")));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertEquals("broken", result.error());
  }

  @Test
  void allStepsReceiveSameContext() {
    var ctx = StepContext.of("shared-input");
    var step =
        Step.parallel(
            "par",
            Step.function("a", c -> StepResult.success("a", c.input())),
            Step.function("b", c -> StepResult.success("b", c.input())));

    var result = step.execute(ctx);

    assertTrue(result.success());
    assertTrue(result.content().contains("shared-input"));
  }

  @Test
  void singleStepDegenerateCase() {
    var step = Step.parallel("par", Step.function("a", ctx -> StepResult.success("a", "only")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertEquals("only", result.content());
  }

  @Test
  void timeoutReturnsFailure() {
    var step =
        Step.parallel(
            "par",
            Duration.ofMillis(50),
            Step.function(
                "slow",
                ctx -> {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return StepResult.success("slow", "done");
                }));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
    assertTrue(result.error().contains("timed out"));
  }

  @Test
  void timeoutWithSuccessfulSteps() {
    var step =
        Step.parallel(
            "par",
            Duration.ofSeconds(5),
            Step.function("a", ctx -> StepResult.success("a", "fast")),
            Step.function("b", ctx -> StepResult.success("b", "also-fast")));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertTrue(result.content().contains("fast"));
  }

  @Test
  void stepThrowsExceptionReturnsFailure() {
    var step =
        Step.parallel(
            "par",
            Step.function("a", ctx -> StepResult.success("a", "ok")),
            Step.function(
                "b",
                ctx -> {
                  throw new RuntimeException("step exploded");
                }));

    var result = step.execute(StepContext.of("input"));

    assertFalse(result.success());
  }

  @Test
  void totalTimeoutAppliesAcrossAllSteps() {
    var step =
        Step.parallel(
            "par",
            Duration.ofMillis(100),
            Step.function(
                "a",
                ctx -> {
                  try {
                    Thread.sleep(60);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return StepResult.success("a", "a-done");
                }),
            Step.function(
                "b",
                ctx -> {
                  try {
                    Thread.sleep(60);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return StepResult.success("b", "b-done");
                }),
            Step.function(
                "c",
                ctx -> {
                  try {
                    Thread.sleep(60);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return StepResult.success("c", "c-done");
                }));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
  }

  @Test
  void nameAccessor() {
    var step = Step.parallel("myPar", Step.function("a", ctx -> StepResult.success("a", "ok")));
    assertEquals("myPar", step.name());
  }

  @Test
  void stepsRunConcurrently() {
    var aStarted = new AtomicBoolean(false);
    var bStarted = new AtomicBoolean(false);

    var step =
        Step.parallel(
            "par",
            Step.function(
                "a",
                ctx -> {
                  aStarted.set(true);
                  while (!bStarted.get()) {
                    Thread.onSpinWait();
                  }
                  return StepResult.success("a", "a-done");
                }),
            Step.function(
                "b",
                ctx -> {
                  bStarted.set(true);
                  while (!aStarted.get()) {
                    Thread.onSpinWait();
                  }
                  return StepResult.success("b", "b-done");
                }));

    var result = step.execute(StepContext.of("input"));

    assertTrue(result.success());
    assertTrue(aStarted.get());
    assertTrue(bStarted.get());
  }
}
