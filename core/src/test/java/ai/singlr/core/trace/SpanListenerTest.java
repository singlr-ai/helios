/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.Agent;
import ai.singlr.core.agent.AgentConfig;
import ai.singlr.core.agent.Team;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SpanListenerTest {

  private static final class RecordingSpanListener implements SpanListener {
    final List<Object> events = new CopyOnWriteArrayList<>();

    @Override
    public void onSpanStart(SpanStart event) {
      events.add(event);
    }

    @Override
    public void onSpanEnd(Span span) {
      events.add(span);
    }
  }

  private static Tool echoTool(String name, AtomicInteger calls) {
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
              calls.incrementAndGet();
              return ToolResult.success(String.valueOf(args.get("value")));
            })
        .build();
  }

  private static Model toolThenStopModel(
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

  private static Model singleResponseModel(String content) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(content)
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
  void firesStartAndEndForEachSpanInOrder() {
    var listener = new RecordingSpanListener();
    var calls = new AtomicInteger();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("a")
                .withModel(toolThenStopModel("echo", "value", "hello", "done"))
                .withTool(echoTool("echo", calls))
                .withSpanListener(listener)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("go");
    assertTrue(result.isSuccess());

    assertTrue(
        listener.events.size() >= 4,
        "expected at least 2 model.chat + 1 tool.echo (each start+end), got " + listener.events);

    var startsByName = new ArrayList<String>();
    var endsByName = new ArrayList<String>();
    for (var e : listener.events) {
      if (e instanceof SpanStart s) startsByName.add(s.name());
      else if (e instanceof Span s) endsByName.add(s.name());
    }
    assertEquals(startsByName.size(), endsByName.size(), "every start has a matching end");
    assertEquals(startsByName, endsByName, "spans complete in the same order they start");
    assertTrue(startsByName.contains("model.chat"));
    assertTrue(startsByName.contains("tool.echo"));
  }

  @Test
  void startEventCarriesIdNameKindAndStartTime() {
    var listener = new RecordingSpanListener();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("a")
                .withModel(singleResponseModel("ok"))
                .withSpanListener(listener)
                .withIncludeMemoryTools(false)
                .build());
    agent.run("hi");

    var firstStart =
        (SpanStart)
            listener.events.stream().filter(e -> e instanceof SpanStart).findFirst().orElseThrow();
    assertEquals("model.chat", firstStart.name());
    assertEquals(SpanKind.MODEL_CALL, firstStart.kind());
    assertNotNull(firstStart.spanId());
    assertNotNull(firstStart.traceId());
    assertNull(firstStart.parentSpanId(), "top-level span has no parent");
    assertNotNull(firstStart.startTime());
  }

  @Test
  void startAndEndShareTheSameSpanId() {
    var listener = new RecordingSpanListener();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("a")
                .withModel(singleResponseModel("ok"))
                .withSpanListener(listener)
                .withIncludeMemoryTools(false)
                .build());
    agent.run("hi");

    SpanStart start = null;
    Span end = null;
    for (var e : listener.events) {
      if (e instanceof SpanStart s) start = s;
      else if (e instanceof Span s) end = s;
    }
    assertNotNull(start);
    assertNotNull(end);
    assertEquals(start.spanId(), end.id(), "span id is stable across start and end events");
    assertEquals(start.name(), end.name());
    assertEquals(start.kind(), end.kind());
  }

  @Test
  void enablesTracingEvenWithNoTraceListener() {
    var listener = new RecordingSpanListener();
    var config =
        AgentConfig.newBuilder()
            .withModel(singleResponseModel("ok"))
            .withSpanListener(listener)
            .withIncludeMemoryTools(false)
            .build();
    assertTrue(config.tracingEnabled(), "spanListener alone should enable tracing");

    new Agent(config).run("hi");
    assertTrue(
        listener.events.stream().anyMatch(e -> e instanceof SpanStart),
        "spans must fire when only a SpanListener is configured");
  }

  @Test
  void exceptionFromOneListenerDoesNotSuppressOthers() {
    var good = new RecordingSpanListener();
    SpanListener bad =
        new SpanListener() {
          @Override
          public void onSpanStart(SpanStart event) {
            throw new RuntimeException("boom");
          }

          @Override
          public void onSpanEnd(Span span) {
            throw new RuntimeException("boom");
          }
        };
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(singleResponseModel("ok"))
                .withSpanListener(bad)
                .withSpanListener(good)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("hi");
    assertTrue(result.isSuccess(), "agent must succeed despite a throwing listener");
    assertTrue(good.events.stream().anyMatch(e -> e instanceof SpanStart));
    assertTrue(good.events.stream().anyMatch(e -> e instanceof Span));
  }

  @Test
  void traceListenerAndSpanListenerCoexist() {
    var traces = new CollectingTraceListener();
    var spans = new RecordingSpanListener();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(singleResponseModel("ok"))
                .withTraceListener(traces)
                .withSpanListener(spans)
                .withIncludeMemoryTools(false)
                .build());
    agent.run("hi");

    assertEquals(1, traces.size());
    assertTrue(spans.events.stream().anyMatch(e -> e instanceof SpanStart));
    assertTrue(spans.events.stream().anyMatch(e -> e instanceof Span));
  }

  @Test
  void nestedWorkerSpanListenerDoesNotFireWhenNestedInTeam() {
    var leaderListener = new RecordingSpanListener();
    var workerListener = new RecordingSpanListener();
    var calls = new AtomicInteger();

    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("worker")
                .withModel(toolThenStopModel("inner_tool", "value", "x", "worker-done"))
                .withTool(echoTool("inner_tool", calls))
                .withSpanListener(workerListener)
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(toolThenStopModel("worker_a", "task", "do work", "team-done"))
            .withSpanListener(leaderListener)
            .withWorker("worker_a", "delegate", worker)
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");

    assertEquals(
        0,
        workerListener.events.size(),
        "worker SpanListener must not fire when nested inside a Team's leader trace");

    assertTrue(
        leaderListener.events.stream()
            .anyMatch(e -> e instanceof SpanStart s && s.name().equals("model.chat")),
        "leader sees its own model.chat");
    assertTrue(
        leaderListener.events.stream()
            .anyMatch(e -> e instanceof SpanStart s && s.name().equals("tool.worker_a")),
        "leader sees the delegation span");
    assertTrue(
        leaderListener.events.stream()
            .anyMatch(e -> e instanceof SpanStart s && s.name().equals("tool.inner_tool")),
        "leader sees the worker's inner tool span via nested propagation");
  }

  @Test
  void parentSpanIdSetsForNestedChildren() {
    var listener = new RecordingSpanListener();
    var calls = new AtomicInteger();
    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(toolThenStopModel("w", "task", "do work", "done"))
            .withSpanListener(listener)
            .withWorker(
                "w",
                "worker",
                new Agent(
                    AgentConfig.newBuilder()
                        .withName("worker")
                        .withModel(toolThenStopModel("leaf", "value", "x", "ok"))
                        .withTool(echoTool("leaf", calls))
                        .withIncludeMemoryTools(false)
                        .build()))
            .withIncludeMemoryTools(false)
            .build();

    team.run("go");

    UUID delegationSpanId = null;
    for (var e : listener.events) {
      if (e instanceof SpanStart s && s.name().equals("tool.w")) {
        delegationSpanId = s.spanId();
        assertNull(s.parentSpanId(), "tool.w is a top-level child of the leader trace");
        break;
      }
    }
    assertNotNull(delegationSpanId, "expected a tool.w start event");

    var leafEvent =
        listener.events.stream()
            .filter(e -> e instanceof SpanStart s && s.name().equals("tool.leaf"))
            .map(SpanStart.class::cast)
            .findFirst()
            .orElseThrow();
    assertNotEquals(
        leafEvent.spanId(), delegationSpanId, "leaf has its own id distinct from delegation span");
    assertNotNull(leafEvent.parentSpanId(), "leaf must have a parent span id when nested");
  }

  @Test
  void traceIdIsStableAcrossAllSpansInARun() {
    var listener = new RecordingSpanListener();
    var calls = new AtomicInteger();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withModel(toolThenStopModel("t", "value", "v", "done"))
                .withTool(echoTool("t", calls))
                .withSpanListener(listener)
                .withIncludeMemoryTools(false)
                .build());
    agent.run("go");

    UUID traceId = null;
    for (var e : listener.events) {
      if (e instanceof SpanStart s) {
        if (traceId == null) {
          traceId = s.traceId();
        } else {
          assertEquals(traceId, s.traceId(), "every span carries the same traceId");
        }
      }
    }
    assertNotNull(traceId);
  }

  @Test
  void noListenerNoOverhead() {
    var t = TraceBuilder.start("t");
    var s = t.span("a", SpanKind.MODEL_CALL);
    var grand = s.span("b", SpanKind.TOOL_EXECUTION);
    grand.end();
    s.end();
    var trace = t.end();
    assertEquals(1, trace.spans().size());
    assertEquals(1, trace.spans().get(0).children().size());
  }

  @Test
  void directBuilderUsageWithSpanListener() {
    var listener = new RecordingSpanListener();
    var t = TraceBuilder.start("t", List.of(), List.of(listener));
    var s = t.span("a", SpanKind.MODEL_CALL);
    s.end();
    t.end();

    assertEquals(2, listener.events.size());
    var start = (SpanStart) listener.events.get(0);
    var end = (Span) listener.events.get(1);
    assertEquals("a", start.name());
    assertEquals(start.spanId(), end.id());
    assertSame(SpanKind.MODEL_CALL, end.kind());
  }

  @Test
  void failedSpanFiresEndEventWithError() {
    var listener = new RecordingSpanListener();
    var t = TraceBuilder.start("t", List.of(), List.of(listener));
    var s = t.span("doomed", SpanKind.TOOL_EXECUTION);
    s.fail("kaboom");
    t.end();

    var end =
        (Span) listener.events.stream().filter(e -> e instanceof Span).findFirst().orElseThrow();
    assertEquals("kaboom", end.error());
  }
}
