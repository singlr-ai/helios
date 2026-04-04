/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeamStreamTest {

  @Test
  void teamStreamDelegation() {
    var workerModel = new MockModel("Worker research findings.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (leaderCallCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("researcher")
                      .withArguments(Map.of("task", "Research topic"))
                      .build();
              return CloseableIterator.of(
                  List.<StreamEvent>of(
                          new StreamEvent.ToolCallComplete(tc),
                          new StreamEvent.Done(
                              Response.newBuilder()
                                  .withToolCalls(List.of(tc))
                                  .withFinishReason(FinishReason.TOOL_CALLS)
                                  .build()))
                      .iterator());
            }
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Summary of "),
                        new StreamEvent.TextDelta("research"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Summary of research")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "leader-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(leaderModel)
            .withWorker("researcher", "Research", worker)
            .withIncludeMemoryTools(false)
            .build();

    var events = collectAll(team.runStream("Research topic"));

    assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallComplete));
    assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta));
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Summary of research", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void teamStreamStringOverload() {
    var workerModel = new MockModel("Worker output.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Done"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Done")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "leader-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(leaderModel)
            .withWorker("worker", "Does work", worker)
            .withIncludeMemoryTools(false)
            .build();

    var events = collectAll(team.runStream("Hello"));

    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void teamStreamWithSession() {
    var workerModel = new MockModel("Worker output.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Session result"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Session result")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "leader-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var team =
        Team.newBuilder()
            .withName("team")
            .withModel(leaderModel)
            .withWorker("worker", "Does work", worker)
            .withIncludeMemoryTools(false)
            .build();

    var events = collectAll(team.runStream(SessionContext.of("Session input")));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Session result", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void teamStreamWithTracing() {
    var workerModel = new MockModel("Worker research output.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (leaderCallCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("researcher")
                      .withArguments(Map.of("task", "Find facts"))
                      .build();
              return CloseableIterator.of(
                  List.<StreamEvent>of(
                          new StreamEvent.ToolCallComplete(tc),
                          new StreamEvent.Done(
                              Response.newBuilder()
                                  .withToolCalls(List.of(tc))
                                  .withFinishReason(FinishReason.TOOL_CALLS)
                                  .build()))
                      .iterator());
            }
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Done"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Done")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "leader-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var traces = new ArrayList<Trace>();
    var team =
        Team.newBuilder()
            .withName("traced-team")
            .withModel(leaderModel)
            .withWorker("researcher", "Finds information", worker)
            .withTraceListener(traces::add)
            .withIncludeMemoryTools(false)
            .build();

    var events = collectAll(team.runStream("Research something"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    var trace = traces.getFirst();
    assertEquals("traced-team", trace.name());
    assertTrue(trace.success());

    var toolSpan =
        trace.spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .filter(s -> "researcher".equals(s.attributes().get("toolName")))
            .findFirst();
    assertTrue(toolSpan.isPresent());
    assertTrue(toolSpan.get().success());
  }

  @Test
  void teamStreamMultipleWorkers() {
    var researcherModel = new MockModel("Key facts.");
    var researcher =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(researcherModel)
                .withIncludeMemoryTools(false)
                .build());

    var writerModel = new MockModel("Polished content.");
    var writer =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Writer")
                .withModel(writerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var turn = leaderCallCount.getAndIncrement();
            if (turn == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("researcher")
                      .withArguments(Map.of("task", "Research"))
                      .build();
              return CloseableIterator.of(
                  List.<StreamEvent>of(
                          new StreamEvent.ToolCallComplete(tc),
                          new StreamEvent.Done(
                              Response.newBuilder()
                                  .withToolCalls(List.of(tc))
                                  .withFinishReason(FinishReason.TOOL_CALLS)
                                  .build()))
                      .iterator());
            }
            if (turn == 1) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_2")
                      .withName("writer")
                      .withArguments(Map.of("task", "Write from research"))
                      .build();
              return CloseableIterator.of(
                  List.<StreamEvent>of(
                          new StreamEvent.ToolCallComplete(tc),
                          new StreamEvent.Done(
                              Response.newBuilder()
                                  .withToolCalls(List.of(tc))
                                  .withFinishReason(FinishReason.TOOL_CALLS)
                                  .build()))
                      .iterator());
            }
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Final published post"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Final published post")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "leader-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var team =
        Team.newBuilder()
            .withName("content-team")
            .withModel(leaderModel)
            .withWorker("researcher", "Finds information", researcher)
            .withWorker("writer", "Writes content", writer)
            .withIncludeMemoryTools(false)
            .build();

    var events = collectAll(team.runStream("Write a blog post"));

    assertEquals(3, leaderCallCount.get());
    var toolCompletes =
        events.stream().filter(e -> e instanceof StreamEvent.ToolCallComplete).count();
    assertEquals(2, toolCompletes);
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(
        "Final published post", ((StreamEvent.Done) events.getLast()).response().content());
  }

  private List<StreamEvent> collectAll(CloseableIterator<StreamEvent> stream) {
    var events = new ArrayList<StreamEvent>();
    try (stream) {
      while (stream.hasNext()) {
        events.add(stream.next());
      }
    }
    return events;
  }
}
