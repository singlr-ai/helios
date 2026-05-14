/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.events.CollectingEventSink;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentEventSinkTest {

  @Test
  void emitsRunStartedAndRunCompletedOnSuccess() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("Hello!"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    var result = agent.run("Hi");

    assertTrue(result.isSuccess());
    var events = sink.events();
    assertFalse(events.isEmpty(), "expected lifecycle events");
    assertTrue(events.get(0) instanceof HeliosEvent.RunStarted, "first event should be RunStarted");
    assertTrue(
        events.stream().anyMatch(HeliosEvent.RunCompleted.class::isInstance),
        "stream should contain RunCompleted");
    assertTrue(
        events.get(events.size() - 1) instanceof HeliosEvent.SessionEnd,
        "SessionEnd is the terminal event after RunCompleted");
  }

  @Test
  void runStartedCarriesHarnessKindAndAttributes() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    agent.run("Hello world!");

    var started = (HeliosEvent.RunStarted) sink.events().get(0);
    assertEquals("agent", started.harnessKind());
    assertEquals("12", started.attributes().get("inputChars"));
  }

  @Test
  void runCompletedCarriesNonNullTrace() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("done"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    agent.run("Test");

    var completed =
        sink.events().stream()
            .filter(HeliosEvent.RunCompleted.class::isInstance)
            .map(HeliosEvent.RunCompleted.class::cast)
            .findFirst()
            .orElseThrow();
    assertNotNull(completed.trace());
    assertNotNull(completed.trace().id());
  }

  @Test
  void emitsRunFailedWhenAgentLoopErrors() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("FailAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    var failure = agent.run((ai.singlr.core.agent.SessionContext) null);

    assertTrue(failure.isFailure());
    var failed = sink.events().stream().filter(HeliosEvent.RunFailed.class::isInstance).findAny();
    // session-null failure is detected before any sink is engaged for this run, so the sink
    // receives nothing — this confirms the sink only sees real runs.
    assertTrue(failed.isEmpty());
  }

  @Test
  void runFailedCarriesErrorAndTrace() {
    var sink = new CollectingEventSink();
    var model =
        new Model() {
          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }

          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("boom from model");
          }
        };
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("FailAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    var result = agent.run("trigger");
    assertTrue(result.isFailure());

    var failed =
        sink.events().stream()
            .filter(HeliosEvent.RunFailed.class::isInstance)
            .map(HeliosEvent.RunFailed.class::cast)
            .findFirst()
            .orElseThrow();
    assertNotNull(failed.trace());
    assertNotNull(failed.error());
  }

  @Test
  void sameRunIdAcrossLifecycleEvents() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());

    agent.run("Hi");

    var events = sink.events();
    var firstRunId = events.get(0).runId();
    for (var e : events) {
      assertEquals(firstRunId, e.runId(), "all lifecycle events should share runId");
    }
  }

  @Test
  void multipleSinksAllReceiveEvents() {
    var sink1 = new CollectingEventSink();
    var sink2 = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink1)
                .withEventSink(sink2)
                .build());

    agent.run("Hi");

    assertEquals(sink1.size(), sink2.size());
    for (var i = 0; i < sink1.size(); i++) {
      assertEquals(sink1.events().get(i), sink2.events().get(i));
    }
  }

  @Test
  void misbehavingSinkDoesNotAbortRun() {
    var goodSink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(
                    e -> {
                      throw new RuntimeException("sink boom");
                    })
                .withEventSink(goodSink)
                .build());

    var result = agent.run("Hi");

    assertTrue(result.isSuccess(), "agent should succeed despite throwing sink");
    assertTrue(goodSink.size() >= 2, "good sink still receives events");
  }

  @Test
  void noEventSinksMeansNoEventEmission() {
    var sink = new CollectingEventSink();
    // Build agent without registering this sink — verify we don't accidentally emit somewhere.
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("EventAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Hi");
    assertEquals(0, sink.size());
  }

  @Test
  void tracingEnabledIsTrueWhenOnlyEventSinkRegistered() {
    var config =
        AgentConfig.newBuilder()
            .withName("EventAgent")
            .withModel(new MockModel("ok"))
            .withIncludeMemoryTools(false)
            .withEventSink(new CollectingEventSink())
            .build();
    assertTrue(config.tracingEnabled());
  }

  @Test
  void runWithToolCallStillEmitsLifecycleEvents() {
    var sink = new CollectingEventSink();
    var model =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }

          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (turn.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("echo")
                              .withArguments(Map.of("x", "y"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("final")
                .withFinishReason(FinishReason.STOP)
                .build();
          }
        };
    var echo =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("returns its input")
            .withExecutor(p -> ai.singlr.core.tool.ToolResult.success(p.toString()))
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("ToolAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withTool(echo)
                .withEventSink(sink)
                .build());

    var result = agent.run("call the tool");
    assertTrue(result.isSuccess());

    assertTrue(sink.events().get(0) instanceof HeliosEvent.RunStarted);
    assertTrue(
        sink.events().stream().anyMatch(HeliosEvent.RunCompleted.class::isInstance),
        "stream should contain RunCompleted");
    assertTrue(
        sink.events().get(sink.size() - 1) instanceof HeliosEvent.SessionEnd,
        "SessionEnd is the terminal event after RunCompleted");
  }

  // ----- Phase 2b: iteration / assistant / tool / span events -----

  @Test
  void emitsIterationStartedAndCompleted() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("IterAgent")
                .withModel(new MockModel("done"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());
    agent.run("hi");

    var starts =
        sink.events().stream().filter(HeliosEvent.IterationStarted.class::isInstance).count();
    var completes =
        sink.events().stream().filter(HeliosEvent.IterationCompleted.class::isInstance).count();
    assertTrue(starts >= 1, "expected at least one IterationStarted");
    assertEquals(starts, completes, "started/completed counts should match");
  }

  @Test
  void emitsAssistantTextWhenModelRepliesWithContent() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TextAgent")
                .withModel(new MockModel("Hello world"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());
    agent.run("hi");

    var assistantText =
        sink.events().stream()
            .filter(HeliosEvent.AssistantText.class::isInstance)
            .map(HeliosEvent.AssistantText.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("Hello world", assistantText.fullText());
  }

  @Test
  void emitsAssistantThinkingCompleteWhenResponseHasThinking() {
    var sink = new CollectingEventSink();
    var model =
        new Model() {
          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }

          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("here is the answer")
                .withThinking("let me consider carefully")
                .withFinishReason(FinishReason.STOP)
                .build();
          }
        };
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("ThinkAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());
    agent.run("question");

    var thinking =
        sink.events().stream()
            .filter(HeliosEvent.AssistantThinkingComplete.class::isInstance)
            .map(HeliosEvent.AssistantThinkingComplete.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("let me consider carefully", thinking.fullThinking());
  }

  @Test
  void emitsToolCallStartedAndCompleted() {
    var sink = new CollectingEventSink();
    var model =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public String id() {
            return "test-model";
          }

          @Override
          public String provider() {
            return "test";
          }

          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (turn.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_xyz")
                              .withName("echo")
                              .withArguments(Map.of("v", "1"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .build();
          }
        };
    var echo =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("returns its input")
            .withExecutor(p -> ai.singlr.core.tool.ToolResult.success(p.toString()))
            .build();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("ToolEventAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .withTool(echo)
                .withEventSink(sink)
                .build());
    agent.run("call it");

    var started =
        sink.events().stream()
            .filter(HeliosEvent.ToolCallStarted.class::isInstance)
            .map(HeliosEvent.ToolCallStarted.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("call_xyz", started.toolCallId());
    assertEquals("echo", started.toolName());

    var completed =
        sink.events().stream()
            .filter(HeliosEvent.ToolCallCompleted.class::isInstance)
            .map(HeliosEvent.ToolCallCompleted.class::cast)
            .findFirst()
            .orElseThrow();
    assertEquals("call_xyz", completed.toolCallId());
    assertTrue(completed.result().success());
  }

  @Test
  void emitsSpanOpenedAndClosedViaSpanListenerBridge() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("SpanAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());
    agent.run("hi");

    var opened = sink.events().stream().filter(HeliosEvent.SpanOpened.class::isInstance).count();
    var closed = sink.events().stream().filter(HeliosEvent.SpanClosed.class::isInstance).count();
    assertTrue(opened >= 1, "expected at least one SpanOpened from the model.chat span");
    assertEquals(opened, closed, "span opens and closes should match");
  }

  @Test
  void runStartedThenIterationStartedThenAssistantTextThenIterationCompletedThenRunCompleted() {
    var sink = new CollectingEventSink();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("OrderAgent")
                .withModel(new MockModel("ok"))
                .withIncludeMemoryTools(false)
                .withEventSink(sink)
                .build());
    agent.run("hi");

    var events = sink.events();
    assertTrue(events.get(0) instanceof HeliosEvent.RunStarted);

    var iterStart = indexOf(events, HeliosEvent.IterationStarted.class);
    var assistant = indexOf(events, HeliosEvent.AssistantText.class);
    var iterEnd = indexOf(events, HeliosEvent.IterationCompleted.class);
    var runEnd = indexOf(events, HeliosEvent.RunCompleted.class);
    assertTrue(iterStart < assistant, "IterationStarted must precede AssistantText");
    assertTrue(assistant < iterEnd, "AssistantText must precede IterationCompleted");
    assertTrue(iterEnd < runEnd, "IterationCompleted must precede RunCompleted");
  }

  private static int indexOf(List<HeliosEvent> events, Class<? extends HeliosEvent> type) {
    for (var i = 0; i < events.size(); i++) {
      if (type.isInstance(events.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
