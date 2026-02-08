/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.common.Result;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowTest {

  @Test
  void singleStepWorkflow() {
    var workflow =
        Workflow.newBuilder("simple")
            .withStep(Step.function("greet", ctx -> StepResult.success("greet", "Hello!")))
            .build();

    var result = workflow.run("input");

    assertTrue(result.isSuccess());
    var value = ((Result.Success<StepResult>) result).value();
    assertEquals("Hello!", value.content());
  }

  @Test
  void multiStepWorkflowWithDataFlow() {
    var workflow =
        Workflow.newBuilder("pipeline")
            .withStep(
                Step.function("step1", ctx -> StepResult.success("step1", "classified: urgent")))
            .withStep(
                Step.function(
                    "step2",
                    ctx -> {
                      var prev = ctx.lastResult().content();
                      return StepResult.success("step2", "handled: " + prev);
                    }))
            .build();

    var result = workflow.run("ticket");

    assertTrue(result.isSuccess());
    var value = ((Result.Success<StepResult>) result).value();
    assertEquals("handled: classified: urgent", value.content());
  }

  @Test
  void stepFailureReturnsResultFailure() {
    var workflow =
        Workflow.newBuilder("failing")
            .withStep(Step.function("bad", ctx -> StepResult.failure("bad", "step failed")))
            .build();

    var result = workflow.run("input");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<StepResult>) result;
    assertEquals("step failed", failure.error());
  }

  @Test
  void workflowWithTracingProducesTrace() {
    var traces = new ArrayList<Trace>();
    var workflow =
        Workflow.newBuilder("traced")
            .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
            .withStep(Step.function("b", ctx -> StepResult.success("b", "ok")))
            .withTraceListener(traces::add)
            .build();

    var result = workflow.run("input");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var trace = traces.getFirst();
    assertEquals("workflow.traced", trace.name());
    assertTrue(trace.success());
    assertEquals(2, trace.spans().size());
    assertEquals("step.a", trace.spans().get(0).name());
    assertEquals("step.b", trace.spans().get(1).name());
    assertEquals(SpanKind.WORKFLOW, trace.spans().get(0).kind());
  }

  @Test
  void workflowWithTracingAndFailureRecordsErrorSpan() {
    var traces = new ArrayList<Trace>();
    var workflow =
        Workflow.newBuilder("traced-fail")
            .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
            .withStep(Step.function("b", ctx -> StepResult.failure("b", "boom")))
            .withTraceListener(traces::add)
            .build();

    var result = workflow.run("input");

    assertTrue(result.isFailure());
    assertEquals(1, traces.size());
    var trace = traces.getFirst();
    assertFalse(trace.success());
    assertNotNull(trace.error());
    assertEquals(2, trace.spans().size());
    assertTrue(trace.spans().get(0).success());
    assertFalse(trace.spans().get(1).success());
  }

  @Test
  void builderValidation() {
    assertThrows(
        IllegalStateException.class,
        () -> Workflow.newBuilder("empty").build(),
        "should require at least one step");

    assertThrows(
        IllegalStateException.class,
        () ->
            Workflow.newBuilder("")
                .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
                .build(),
        "should require non-blank name");

    assertThrows(
        IllegalStateException.class,
        () ->
            Workflow.newBuilder("  ")
                .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
                .build(),
        "should require non-blank name");
  }

  @Test
  void nameAndStepsAccessors() {
    var step = Step.function("a", ctx -> StepResult.success("a", "ok"));
    var workflow = Workflow.newBuilder("test").withStep(step).build();

    assertEquals("test", workflow.name());
    assertEquals(1, workflow.steps().size());
    assertEquals("a", workflow.steps().getFirst().name());
  }

  @Test
  void exceptionInStepCaughtAsFailure() {
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .build());

    var workflow =
        Workflow.newBuilder("exploding")
            .withStep(
                Step.agent(
                    "broken",
                    agent,
                    ctx -> {
                      throw new RuntimeException("mapper exploded");
                    }))
            .build();

    var result = workflow.run("input");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<StepResult>) result;
    assertTrue(failure.error().contains("mapper exploded"));
  }

  @Test
  void exceptionWithTracingRecordsFailedTrace() {
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .build());

    var traces = new ArrayList<Trace>();
    var workflow =
        Workflow.newBuilder("exploding-traced")
            .withStep(
                Step.agent(
                    "broken",
                    agent,
                    ctx -> {
                      throw new RuntimeException("boom");
                    }))
            .withTraceListener(traces::add)
            .build();

    var result = workflow.run("input");

    assertTrue(result.isFailure());
    assertEquals(1, traces.size());
    assertFalse(traces.getFirst().success());
  }

  @Test
  void builderWithStepsList() {
    var steps =
        List.<Step>of(
            Step.function("a", ctx -> StepResult.success("a", "first")),
            Step.function("b", ctx -> StepResult.success("b", "second")));

    var workflow = Workflow.newBuilder("bulk").withSteps(steps).build();

    assertEquals(2, workflow.steps().size());
    var result = workflow.run("input");
    assertTrue(result.isSuccess());
  }

  @Test
  void builderWithTraceListenersList() {
    var traces1 = new ArrayList<Trace>();
    var traces2 = new ArrayList<Trace>();

    var workflow =
        Workflow.newBuilder("multi-trace")
            .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
            .withTraceListeners(List.of(traces1::add, traces2::add))
            .build();

    workflow.run("input");

    assertEquals(1, traces1.size());
    assertEquals(1, traces2.size());
  }

  @Test
  void builderNullNameThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Workflow.newBuilder(null)
                .withStep(Step.function("a", ctx -> StepResult.success("a", "ok")))
                .build());
  }
}
