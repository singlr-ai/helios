/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.test.TraceCollector;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanKind;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeakedSpanReproducerTest {

  private static Model twoStepModel(
      String toolName, String argKey, String argValue, String finalText) {
    var turn = new AtomicInteger();
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        if (turn.getAndIncrement() == 0) {
          return Response.newBuilder()
              .withToolCalls(
                  List.of(
                      ToolCall.newBuilder()
                          .withId("c1")
                          .withName(toolName)
                          .withArguments(Map.of(argKey, argValue))
                          .build()))
              .withFinishReason(FinishReason.TOOL_CALLS)
              .build();
        }
        return Response.newBuilder()
            .withContent(finalText)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      public String id() {
        return "mock";
      }

      @Override
      public String provider() {
        return "test";
      }
    };
  }

  /** Worker tool that throws RuntimeException — simulates a sandbox callTimeout. */
  private static Tool throwingTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("always throws")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("code")
                .withType(ParameterType.STRING)
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              throw new RuntimeException("Simulated sandbox callTimeout: 2s");
            })
        .build();
  }

  @Test
  void workerWithParallelToolsThrowingDoesNotLeak() {
    var workerTurn = new AtomicInteger();
    var workerModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (workerTurn.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c1")
                              .withName("tool_a")
                              .withArguments(Map.of("code", "x"))
                              .build(),
                          ToolCall.newBuilder()
                              .withId("c2")
                              .withName("tool_b")
                              .withArguments(Map.of("code", "y"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("worker-final")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "w";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("doomed_worker")
                .withModel(workerModel)
                .withTool(throwingTool("tool_a"))
                .withTool(throwingTool("tool_b"))
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var collector = new TraceCollector();
    var team =
        Team.newBuilder()
            .withName("test-team")
            .withModel(twoStepModel("doomed_worker", "task", "go", "leader-final"))
            .withEventSink(collector)
            .withWorker("doomed_worker", "will throw", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("go");
    assertNotNull(result, "team.run must not throw IllegalStateException from leaked span");
  }

  /**
   * Tool that opens nothing but blocks long enough for the leader's FT timeout to fire. The worker
   * completes its model.chat span first (synchronous, fast), then opens a tool span and gets stuck
   * inside the tool — the FT timeout interrupts the virtual thread mid-tool, so the tool span may
   * be left open.
   */
  private static Tool slowTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("blocks until interrupted")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("code")
                .withType(ParameterType.STRING)
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
              }
              return ToolResult.success("never");
            })
        .build();
  }

  @Test
  void leaderWithFtTimeoutAndConcurrentWorkersDoesNotLeak() {
    // Leader has FT timeout + parallel tool execution. Calls 2 workers in parallel.
    // Worker_a is slow (gets interrupted), worker_b is fast.
    var fastWorker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("fast")
                .withModel(twoStepModel("nop", "code", "x", "fast-done"))
                .withTool(throwingTool("nop"))
                .withIncludeMemoryTools(false)
                .build());
    var slowWorker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("slow")
                .withModel(twoStepModel("nop_slow", "code", "x", "slow-done"))
                .withTool(slowTool("nop_slow"))
                .withIncludeMemoryTools(false)
                .build());

    var leaderTurn = new AtomicInteger();
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (leaderTurn.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c1")
                              .withName("fast")
                              .withArguments(Map.of("task", "go"))
                              .build(),
                          ToolCall.newBuilder()
                              .withId("c2")
                              .withName("slow")
                              .withArguments(Map.of("task", "go"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("leader-final")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "leader";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var collector = new TraceCollector();
    var team =
        Team.newBuilder()
            .withName("test-team")
            .withModel(leaderModel)
            .withEventSink(collector)
            .withParallelToolExecution(true)
            .withFaultTolerance(
                FaultTolerance.newBuilder()
                    .withOperationTimeout(java.time.Duration.ofMillis(300))
                    .build())
            .withWorker("fast", "fast", fastWorker)
            .withWorker("slow", "slow", slowWorker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("go");
    assertNotNull(result, "team.run must not throw IllegalStateException");
  }

  @Test
  void leaderFtTimeoutAroundWorkerWithSlowToolDoesNotLeakSpan() {
    var workerAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("slow_worker")
                .withModel(twoStepModel("slow_tool", "code", "x", "wont-reach"))
                .withTool(slowTool("slow_tool"))
                .withIncludeMemoryTools(false)
                .build());

    var collector = new TraceCollector();
    // Leader has aggressive FT timeout — interrupts worker mid-tool.
    var team =
        Team.newBuilder()
            .withName("test-team")
            .withModel(twoStepModel("slow_worker", "task", "go", "leader-final"))
            .withEventSink(collector)
            .withFaultTolerance(
                FaultTolerance.newBuilder()
                    .withOperationTimeout(java.time.Duration.ofMillis(500))
                    .build())
            .withWorker("slow_worker", "slow", workerAgent)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("go");
    assertNotNull(result, "team.run must not throw IllegalStateException from leaked span");
  }

  /**
   * Tool that deliberately opens a span on PARENT_SPAN and never closes it. Simulates the bug class
   * that bit Kubera in production: some interleaving causes the worker's tool span to be left open,
   * so the leader's traceBuilder.end() finds open children and used to throw IllegalStateException
   * out of team.run(). After the defensive fix, runLoop catches that and force-closes via fail()
   * instead.
   */
  private static Tool spanLeakingTool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("opens a span and forgets to close it")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("code")
                .withType(ParameterType.STRING)
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              if (Agent.PARENT_SPAN.isBound()) {
                var leakedSpan = Agent.PARENT_SPAN.get().span("leaked.subspan", SpanKind.CUSTOM);
                leakedSpan.attribute("intentional", "true");
                // Deliberately do not close — simulates a buggy tool/sub-agent path.
              }
              return ToolResult.success("done");
            })
        .build();
  }

  @Test
  void leakedChildSpanDoesNotPropagateIllegalStateException() {
    // Regression for Kubera 1.0.30 trace-leak bug: a tool leaving an open child span on the
    // leader's tool span used to surface as
    //   IllegalStateException: Cannot end trace 'X': 1 span(s) still open
    // bubbling out of team.run / agent.run. The defensive fix in Agent.finalizeTrace converts this
    // into a force-fail with a logged warning instead of propagating.
    var collector = new TraceCollector();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("leak-test")
                .withModel(twoStepModel("leaking_tool", "code", "x", "all-good"))
                .withTool(spanLeakingTool("leaking_tool"))
                .withEventSink(collector)
                .withIncludeMemoryTools(false)
                .build());

    // Wrap inside a Team so the tool's leaked span lives under tool.<worker> on the leader's trace
    // — exact same shape as Kubera's failure scenario.
    var team =
        Team.newBuilder()
            .withName("test-team")
            .withModel(twoStepModel("leak-test", "task", "go", "leader-final"))
            .withEventSink(collector)
            .withWorker("leak-test", "worker that leaks", agent)
            .withIncludeMemoryTools(false)
            .build();

    // BEFORE fix: throws IllegalStateException. AFTER fix: returns Result, trace is collected.
    var result = team.run("go");
    assertNotNull(result, "team.run must not throw IllegalStateException from leaked span");
    assertTrue(collector.size() >= 1, "trace must still be fired even after force-close fallback");
  }

  @Test
  void workerToolThrowingDoesNotLeakSpan() {
    var workerAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("doomed_worker")
                .withModel(twoStepModel("execute_code", "code", "predict('x')", "wont-reach"))
                .withTool(throwingTool("execute_code"))
                .withIncludeMemoryTools(false)
                .build());

    var collector = new TraceCollector();
    var team =
        Team.newBuilder()
            .withName("test-team")
            .withModel(twoStepModel("doomed_worker", "task", "go", "leader-final"))
            .withEventSink(collector)
            .withWorker("doomed_worker", "will throw", workerAgent)
            .withIncludeMemoryTools(false)
            .build();

    // This must NOT throw IllegalStateException ("X span(s) still open").
    var result = team.run("go");

    // Either Result.Success (leader recovered) or Result.Failure (leader propagated). Both fine.
    // The must-not-throw assertion is the regression — the rest is structural.
    assertNotNull(result, "team.run must return a Result, not throw");

    // Trace must have been collected (whether the leader succeeded or failed).
    assertTrue(collector.size() >= 1, "expected at least one trace fired");
  }
}
