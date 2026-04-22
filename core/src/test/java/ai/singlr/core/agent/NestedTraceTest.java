/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.CollectingTraceListener;
import ai.singlr.core.trace.Span;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NestedTraceTest {

  private static List<Span> flatten(Trace trace) {
    var out = new ArrayList<Span>();
    for (var s : trace.spans()) {
      flatten(s, out);
    }
    return out;
  }

  private static void flatten(Span span, List<Span> out) {
    out.add(span);
    for (var child : span.children()) {
      flatten(child, out);
    }
  }

  private static Tool echoTool(String name, AtomicInteger invocations) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("echo " + name)
        .withParameter(
            ToolParameter.newBuilder()
                .withName("value")
                .withType(ParameterType.STRING)
                .withRequired(true)
                .build())
        .withExecutor(
            args -> {
              invocations.incrementAndGet();
              return ToolResult.success(String.valueOf(args.get("value")));
            })
        .build();
  }

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

  @Test
  void workerInternalSpansNestUnderLeaderToolSpan() {
    var workerToolCount = new AtomicInteger();
    var workerTool = echoTool("portfolio_snapshot", workerToolCount);
    var workerAgent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Specialist")
                .withModel(twoStepModel("portfolio_snapshot", "value", "A", "done"))
                .withTool(workerTool)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCollector = new CollectingTraceListener();
    var workerCollector = new CollectingTraceListener();
    var workerAgentWithListener =
        new Agent(
            AgentConfig.newBuilder(workerAgent.config())
                .withTraceListener(workerCollector)
                .build());

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(twoStepModel("specialist", "task", "query", "final"))
            .withTraceListener(leaderCollector)
            .withWorker("specialist", "does work", workerAgentWithListener)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("go");
    assertTrue(result.isSuccess());

    assertEquals(1, leaderCollector.size(), "leader fires exactly one trace");
    assertEquals(0, workerCollector.size(), "worker does not fire its own trace when nested");

    var trace = leaderCollector.latest();
    var all = flatten(trace);

    var toolDelegationSpan =
        all.stream().filter(s -> s.name().equals("tool.specialist")).findFirst().orElseThrow();
    assertEquals(SpanKind.TOOL_EXECUTION, toolDelegationSpan.kind());

    var childNames = toolDelegationSpan.children().stream().map(Span::name).toList();
    assertTrue(
        childNames.contains("model.chat"),
        "expected worker's model.chat as child of tool.specialist, got: " + childNames);
    assertTrue(
        childNames.contains("tool.portfolio_snapshot"),
        "expected worker's tool.portfolio_snapshot as child, got: " + childNames);
    assertEquals(1, workerToolCount.get());
  }

  @Test
  void standaloneAgentStillFiresOwnTrace() {
    var collector = new CollectingTraceListener();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("standalone")
                .withModel(
                    new Model() {
                      @Override
                      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
                        return Response.newBuilder()
                            .withContent("ok")
                            .withFinishReason(FinishReason.STOP)
                            .build();
                      }

                      @Override
                      public String id() {
                        return "m";
                      }

                      @Override
                      public String provider() {
                        return "test";
                      }
                    })
                .withTraceListener(collector)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("hi");
    assertTrue(result.isSuccess());
    assertEquals(1, collector.size());
  }

  @Test
  void nestedModeInheritsTracingEvenWhenWorkerHasNoListeners() {
    var leaderCollector = new CollectingTraceListener();
    var workerCount = new AtomicInteger();
    var workerTool = echoTool("fetch_data", workerCount);
    var workerNoListeners =
        new Agent(
            AgentConfig.newBuilder()
                .withName("silent-worker")
                .withModel(twoStepModel("fetch_data", "value", "x", "done"))
                .withTool(workerTool)
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(twoStepModel("silent_worker", "task", "q", "out"))
            .withTraceListener(leaderCollector)
            .withWorker("silent_worker", "silent", workerNoListeners)
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");
    var trace = leaderCollector.latest();
    var delegation =
        flatten(trace).stream()
            .filter(s -> s.name().equals("tool.silent_worker"))
            .findFirst()
            .orElseThrow();
    var childToolSpan =
        delegation.children().stream()
            .filter(s -> s.name().equals("tool.fetch_data"))
            .findFirst()
            .orElseThrow();
    assertEquals(SpanKind.TOOL_EXECUTION, childToolSpan.kind());
    assertEquals("fetch_data", childToolSpan.attributes().get("toolName"));
  }

  @Test
  void parallelToolExecutionKeepsNestingPerTool() {
    var worker1Count = new AtomicInteger();
    var worker2Count = new AtomicInteger();

    var worker1 =
        new Agent(
            AgentConfig.newBuilder()
                .withName("w1")
                .withModel(twoStepModel("tool_a", "value", "a", "w1-done"))
                .withTool(echoTool("tool_a", worker1Count))
                .withIncludeMemoryTools(false)
                .build());
    var worker2 =
        new Agent(
            AgentConfig.newBuilder()
                .withName("w2")
                .withModel(twoStepModel("tool_b", "value", "b", "w2-done"))
                .withTool(echoTool("tool_b", worker2Count))
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
                              .withName("w1")
                              .withArguments(Map.of("task", "a"))
                              .build(),
                          ToolCall.newBuilder()
                              .withId("c2")
                              .withName("w2")
                              .withArguments(Map.of("task", "b"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("combined")
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

    var collector = new CollectingTraceListener();
    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(leaderModel)
            .withTraceListener(collector)
            .withParallelToolExecution(true)
            .withWorker("w1", "worker 1", worker1)
            .withWorker("w2", "worker 2", worker2)
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");
    assertEquals(1, collector.size());
    var trace = collector.latest();

    var w1Span =
        flatten(trace).stream().filter(s -> s.name().equals("tool.w1")).findFirst().orElseThrow();
    var w2Span =
        flatten(trace).stream().filter(s -> s.name().equals("tool.w2")).findFirst().orElseThrow();
    assertTrue(
        w1Span.children().stream().anyMatch(s -> s.name().equals("tool.tool_a")),
        "w1 subtree contains its tool");
    assertTrue(
        w2Span.children().stream().anyMatch(s -> s.name().equals("tool.tool_b")),
        "w2 subtree contains its tool");
    assertFalse(
        w1Span.children().stream().anyMatch(s -> s.name().equals("tool.tool_b")),
        "w1's subtree must not contain w2's tool");
    assertFalse(
        w2Span.children().stream().anyMatch(s -> s.name().equals("tool.tool_a")),
        "w2's subtree must not contain w1's tool");
  }

  @Test
  void collectingTraceListenerIsThreadSafe() throws InterruptedException {
    var collector = new CollectingTraceListener();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("a")
                .withModel(
                    new Model() {
                      @Override
                      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
                        return Response.newBuilder()
                            .withContent("ok")
                            .withFinishReason(FinishReason.STOP)
                            .build();
                      }

                      @Override
                      public String id() {
                        return "m";
                      }

                      @Override
                      public String provider() {
                        return "test";
                      }
                    })
                .withTraceListener(collector)
                .withIncludeMemoryTools(false)
                .build());

    var threads = new ArrayList<Thread>();
    for (int i = 0; i < 50; i++) {
      threads.add(Thread.ofVirtual().start(() -> agent.run("x")));
    }
    for (var t : threads) {
      t.join();
    }
    assertEquals(50, collector.size());
  }

  @Test
  void delegationSpanGetsSubAgentDiagnosticAttributes() {
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("worker")
                .withModel(
                    new Model() {
                      @Override
                      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
                        return Response.newBuilder()
                            .withContent("ok")
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
                    })
                .withIncludeMemoryTools(false)
                .build());

    var collector = new CollectingTraceListener();
    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(twoStepModel("w", "task", "q", "done"))
            .withTraceListener(collector)
            .withWorker("w", "worker", worker)
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");
    var trace = collector.latest();
    var toolSpan =
        flatten(trace).stream().filter(s -> s.name().equals("tool.w")).findFirst().orElseThrow();

    assertEquals(
        "true",
        toolSpan.attributes().get("subAgent.nested"),
        "asTool must mark parent span with subAgent.nested=true when PARENT_SPAN was bound");
    assertNotNull(
        toolSpan.attributes().get("subAgent.spanCount"),
        "nested sub-agent must record subAgent.spanCount on parent");
    assertTrue(
        Integer.parseInt(toolSpan.attributes().get("subAgent.spanCount")) >= 1,
        "sub-agent with no inner tools still contributes at least its model.chat span");
  }

  @Test
  void workerReturningContentDirectlyStillNestsModelSpan() {
    var workerModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("grounded answer from training")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "worker";
          }

          @Override
          public String provider() {
            return "test";
          }
        };
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("grounded-worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var collector = new CollectingTraceListener();
    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(twoStepModel("specialist", "task", "q", "done"))
            .withTraceListener(collector)
            .withWorker("specialist", "w", worker)
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");
    var trace = collector.latest();
    var toolSpan =
        flatten(trace).stream()
            .filter(s -> s.name().equals("tool.specialist"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        1,
        toolSpan.children().size(),
        "worker with no inner tool calls should still nest its model.chat span; got: "
            + toolSpan.children().stream().map(Span::name).toList());
    assertEquals("model.chat", toolSpan.children().get(0).name());
  }

  @Test
  void collectingTraceListenerUtilities() {
    var collector = new CollectingTraceListener();
    assertEquals(0, collector.size());
    assertNull(collector.latest());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("a")
                .withModel(
                    new Model() {
                      @Override
                      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
                        return Response.newBuilder()
                            .withContent("ok")
                            .withFinishReason(FinishReason.STOP)
                            .build();
                      }

                      @Override
                      public String id() {
                        return "m";
                      }

                      @Override
                      public String provider() {
                        return "test";
                      }
                    })
                .withTraceListener(collector)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("a");
    agent.run("b");
    assertEquals(2, collector.size());
    assertNotNull(collector.latest());
    var snapshot = collector.traces();
    assertEquals(2, snapshot.size());

    collector.clear();
    assertEquals(0, collector.size());
    assertNull(collector.latest());
    assertEquals(2, snapshot.size(), "snapshot is immutable");
  }
}
