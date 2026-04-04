/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.fault.Backoff;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.fault.RetryPolicy;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentStreamTest {

  @Test
  void simpleTextStream() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var response =
                Response.newBuilder()
                    .withContent("Hello there!")
                    .withFinishReason(FinishReason.STOP)
                    .build();
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Hello "),
                        new StreamEvent.TextDelta("there!"),
                        new StreamEvent.Done(response))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("StreamAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Hi"));

    assertEquals(3, events.size());
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(0));
    assertInstanceOf(StreamEvent.TextDelta.class, events.get(1));
    assertInstanceOf(StreamEvent.Done.class, events.get(2));
    assertEquals("Hello ", ((StreamEvent.TextDelta) events.get(0)).text());
    assertEquals("there!", ((StreamEvent.TextDelta) events.get(1)).text());
    assertEquals("Hello there!", ((StreamEvent.Done) events.get(2)).response().content());
  }

  @Test
  void streamWithToolCall() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("get_time")
                      .withArguments(Map.of())
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
                        new StreamEvent.TextDelta("It's 10:30 AM"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("It's 10:30 AM")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var timeTool =
        Tool.newBuilder()
            .withName("get_time")
            .withDescription("Get current time")
            .withExecutor(args -> ToolResult.success("10:30 AM"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(timeTool)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("What time is it?"));

    assertEquals(2, callCount.get());
    assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolCallComplete));
    assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.TextDelta));
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("It's 10:30 AM", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void streamMultipleToolCalls() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc1 =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("tool_a")
                      .withArguments(Map.of())
                      .build();
              var tc2 =
                  ToolCall.newBuilder()
                      .withId("call_2")
                      .withName("tool_b")
                      .withArguments(Map.of())
                      .build();
              return CloseableIterator.of(
                  List.<StreamEvent>of(
                          new StreamEvent.ToolCallComplete(tc1),
                          new StreamEvent.ToolCallComplete(tc2),
                          new StreamEvent.Done(
                              Response.newBuilder()
                                  .withToolCalls(List.of(tc1, tc2))
                                  .withFinishReason(FinishReason.TOOL_CALLS)
                                  .build()))
                      .iterator());
            }
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Both done"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Both done")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var toolA =
        Tool.newBuilder()
            .withName("tool_a")
            .withDescription("A")
            .withExecutor(args -> ToolResult.success("Result A"))
            .build();
    var toolB =
        Tool.newBuilder()
            .withName("tool_b")
            .withDescription("B")
            .withExecutor(args -> ToolResult.success("Result B"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(toolA)
                .withTool(toolB)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    var toolCompletes =
        events.stream().filter(e -> e instanceof StreamEvent.ToolCallComplete).count();
    assertEquals(2, toolCompletes);
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamMaxIterationsReached() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var tc =
                ToolCall.newBuilder()
                    .withId("call_" + System.nanoTime())
                    .withName("loop")
                    .withArguments(Map.of())
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

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var loopTool =
        Tool.newBuilder()
            .withName("loop")
            .withDescription("Loops")
            .withExecutor(args -> ToolResult.success("Continue"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(loopTool)
                .withMaxIterations(3)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Start loop"));

    var last = events.getLast();
    assertInstanceOf(StreamEvent.Error.class, last);
    assertTrue(((StreamEvent.Error) last).message().contains("Max iterations"));
  }

  @Test
  void streamNullSessionFails() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream((SessionContext) null));

    assertEquals(1, events.size());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertEquals("session must not be null", ((StreamEvent.Error) events.getFirst()).message());
  }

  @Test
  void streamBlankInputFails() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("   "));

    assertEquals(1, events.size());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertEquals(
        "userInput must not be null or blank", ((StreamEvent.Error) events.getFirst()).message());
  }

  @Test
  void streamModelException() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("Model error");
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertEquals(1, events.size());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertTrue(((StreamEvent.Error) events.getFirst()).message().contains("Model error"));
  }

  @Test
  void streamWithMemory() {
    var model =
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
                        new StreamEvent.TextDelta("Remembered"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Remembered")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var memory = InMemoryMemory.newBuilder().withBlock("user", "User info").build();
    memory.updateBlock("user", "name", "Alice");
    var session = SessionContext.of("Who am I?");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());

    var history = memory.history(null, session.sessionId());
    assertFalse(history.isEmpty());
  }

  @Test
  void streamTracing() {
    var model =
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
                        new StreamEvent.TextDelta("Hello"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Hello")
                                .withFinishReason(FinishReason.STOP)
                                .withUsage(Response.Usage.of(10, 5))
                                .build()))
                    .iterator());
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TracedAgent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Hi"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    var trace = traces.getFirst();
    assertEquals("TracedAgent", trace.name());
    assertTrue(trace.success());
    assertEquals(1, trace.spans().size());
    assertEquals(SpanKind.MODEL_CALL, trace.spans().getFirst().kind());
    assertEquals("10", trace.spans().getFirst().attributes().get("inputTokens"));
    assertEquals("5", trace.spans().getFirst().attributes().get("outputTokens"));
  }

  @Test
  void streamEarlyClose() {
    var model =
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
                        new StreamEvent.TextDelta("Hello "),
                        new StreamEvent.TextDelta("world"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Hello world")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var stream = agent.runStream("Hi");
    assertTrue(stream.hasNext());
    stream.next();
    stream.close();
    assertFalse(stream.hasNext());
  }

  @Test
  void streamWithSystemPrompt() {
    var capturedMessages = new ArrayList<List<Message>>();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            capturedMessages.add(List.copyOf(messages));
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("CustomAgent")
                .withModel(model)
                .withSystemPrompt("You are {name}. Be brief.")
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertFalse(capturedMessages.isEmpty());
    assertEquals(
        "You are CustomAgent. Be brief.", capturedMessages.getFirst().getFirst().content());
  }

  @Test
  void streamToolFailure() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("failing_tool")
                      .withArguments(Map.of())
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
                        new StreamEvent.TextDelta("Recovered"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Recovered")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var failingTool =
        Tool.newBuilder()
            .withName("failing_tool")
            .withDescription("Fails")
            .withExecutor(args -> ToolResult.failure("tool error"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(failingTool)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Recovered", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void streamUnknownTool() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("nonexistent")
                      .withArguments(Map.of())
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
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamDoneContainsFinalResponse() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var response =
                Response.newBuilder()
                    .withContent("Final answer")
                    .withFinishReason(FinishReason.STOP)
                    .withUsage(Response.Usage.of(100, 50))
                    .build();
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Final "),
                        new StreamEvent.TextDelta("answer"),
                        new StreamEvent.Done(response))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    var done = (StreamEvent.Done) events.getLast();
    assertEquals("Final answer", done.response().content());
    assertEquals(FinishReason.STOP, done.response().finishReason());
    assertEquals(100, done.response().usage().inputTokens());
    assertEquals(50, done.response().usage().outputTokens());
  }

  @Test
  void streamContextCompaction() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("summary of conversation")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("ok"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
          }

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }

          @Override
          public int contextWindow() {
            return 100;
          }
        };

    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.of("Test compaction");
    for (var i = 0; i < 10; i++) {
      memory.addMessage(null, session.sessionId(), Message.user("Long message " + "x".repeat(50)));
      memory.addMessage(
          null, session.sessionId(), Message.assistant("Long response " + "x".repeat(50)));
    }

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamWithFaultTolerance() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() < 2) {
              throw new RuntimeException("Transient error");
            }
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.TextDelta("Recovered"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Recovered")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var ft =
        FaultTolerance.newBuilder()
            .withRetry(
                RetryPolicy.newBuilder()
                    .withMaxAttempts(3)
                    .withBackoff(Backoff.fixed(Duration.ofMillis(10)))
                    .build())
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withFaultTolerance(ft)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertEquals(3, callCount.get());
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Recovered", ((StreamEvent.Done) events.getLast()).response().content());
  }

  @Test
  void streamStringOverload() {
    var model =
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
                        new StreamEvent.TextDelta("Hi"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Hi")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Hello"));

    assertFalse(events.isEmpty());
    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamIteratorProtocol() {
    var model =
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
                        new StreamEvent.TextDelta("Hello"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Hello")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    try (var stream = agent.runStream("Hi")) {
      assertTrue(stream.hasNext());
      assertTrue(stream.hasNext());
      assertTrue(stream.hasNext());

      var event = stream.next();
      assertInstanceOf(StreamEvent.TextDelta.class, event);

      assertTrue(stream.hasNext());
      var done = stream.next();
      assertInstanceOf(StreamEvent.Done.class, done);

      assertFalse(stream.hasNext());
      assertFalse(stream.hasNext());

      assertThrows(NoSuchElementException.class, stream::next);
    }
  }

  @Test
  void streamForwardsToolCallStartAndDelta() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var tc =
                ToolCall.newBuilder().withId("c1").withName("t").withArguments(Map.of()).build();
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.ToolCallStart("c1", "t"),
                        new StreamEvent.ToolCallDelta("c1", "{\"key\":"),
                        new StreamEvent.ToolCallDelta("c1", "\"val\"}"),
                        new StreamEvent.ToolCallComplete(tc),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertEquals(5, events.size());
    assertInstanceOf(StreamEvent.ToolCallStart.class, events.get(0));
    assertInstanceOf(StreamEvent.ToolCallDelta.class, events.get(1));
    assertInstanceOf(StreamEvent.ToolCallDelta.class, events.get(2));
    assertInstanceOf(StreamEvent.ToolCallComplete.class, events.get(3));
    assertInstanceOf(StreamEvent.Done.class, events.get(4));
  }

  @Test
  void streamErrorEventFromModel() {
    var model =
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
                        new StreamEvent.TextDelta("partial"), new StreamEvent.Error("stream error"))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    var last = events.getLast();
    assertInstanceOf(StreamEvent.Error.class, last);
    assertTrue(((StreamEvent.Error) last).message().contains("stream error"));
  }

  @Test
  void streamEndingWithoutDone() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            return CloseableIterator.of(
                List.<StreamEvent>of(new StreamEvent.TextDelta("incomplete")).iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    var last = events.getLast();
    assertInstanceOf(StreamEvent.Error.class, last);
    assertTrue(((StreamEvent.Error) last).message().contains("Stream ended without Done or Error"));
  }

  @Test
  void streamTracingOnError() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("Model error");
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertEquals(1, traces.size());
    assertFalse(traces.getFirst().success());
  }

  @Test
  void streamTracingWithPromptMetadata() {
    var model =
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
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withPromptName("test-prompt")
                .withPromptVersion(3)
                .withIncludeMemoryTools(false)
                .build());

    var session = SessionContext.newBuilder().withUserInput("Test").withGroupId("eval-1").build();

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    assertTrue(traces.getFirst().success());
  }

  @Test
  void streamVerboseTracing() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("search")
                      .withArguments(Map.of("query", "weather"))
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
                                .withThinking("reasoning...")
                                .build()))
                    .iterator());
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

    var searchTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search")
            .withExecutor(args -> ToolResult.success("sunny 25C"))
            .build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(searchTool)
                .withTraceListener(traces::add)
                .withTraceDetail(ai.singlr.core.trace.TraceDetail.VERBOSE)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Weather?"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    assertTrue(traces.getFirst().success());

    var toolSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .findFirst()
            .orElseThrow();
    assertTrue(toolSpan.attributes().containsKey("arguments"));
    assertEquals("sunny 25C", toolSpan.attributes().get("result"));

    var lastModelSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.MODEL_CALL)
            .reduce((a, b) -> b)
            .orElseThrow();
    assertEquals("reasoning...", lastModelSpan.attributes().get("thinking"));
  }

  @Test
  void streamTracingNullUsageAndFinishReason() {
    var model =
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
                        new StreamEvent.Done(Response.newBuilder().withContent("ok").build()))
                    .iterator());
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    var span = traces.getFirst().spans().getFirst();
    assertFalse(span.attributes().containsKey("inputTokens"));
    assertFalse(span.attributes().containsKey("finishReason"));
  }

  @Test
  void streamTracingOnToolException() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var tc =
                ToolCall.newBuilder()
                    .withId("call_1")
                    .withName("crashing_tool")
                    .withArguments(Map.of())
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

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var crashingTool =
        Tool.newBuilder()
            .withName("crashing_tool")
            .withDescription("Crashes")
            .withExecutor(
                args -> {
                  throw new RuntimeException("Tool crash");
                })
            .build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(crashingTool)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertEquals(1, traces.size());
    assertFalse(traces.getFirst().success());
  }

  @Test
  void streamTracingNullContent() {
    var model =
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
                        new StreamEvent.Done(
                            Response.newBuilder().withFinishReason(FinishReason.STOP).build()))
                    .iterator());
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    assertTrue(traces.getFirst().success());
  }

  @Test
  void iteratorDetectsThreadDeath() throws Exception {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var thread = Thread.ofVirtual().start(() -> {});
    thread.join(5000);

    var iterator = new AgentStreamIterator(queue, thread);

    assertTrue(iterator.hasNext());
    var event = iterator.next();
    assertInstanceOf(StreamEvent.Error.class, event);
    assertTrue(((StreamEvent.Error) event).message().contains("terminated unexpectedly"));

    assertFalse(iterator.hasNext());
    iterator.close();
  }

  @Test
  void iteratorInterruptedDuringPoll() {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var blockLatch = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    blockLatch.await();
                  } catch (InterruptedException e) {
                    /* expected */
                  }
                });
    var iterator = new AgentStreamIterator(queue, thread);

    Thread.currentThread().interrupt();

    assertFalse(iterator.hasNext());
    assertTrue(Thread.interrupted());

    iterator.close();
    blockLatch.countDown();
  }

  @Test
  void streamNullUserInput() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var session = SessionContext.newBuilder().build();

    var events = collectAll(agent.runStream(session));

    assertEquals(1, events.size());
    assertInstanceOf(StreamEvent.Error.class, events.getFirst());
    assertEquals(
        "userInput must not be null or blank", ((StreamEvent.Error) events.getFirst()).message());
  }

  @Test
  void streamWithMemoryUserIdAndToolCalls() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("lookup")
                      .withArguments(Map.of("q", "info"))
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
                        new StreamEvent.TextDelta("Found it"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Found it")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var lookupTool =
        Tool.newBuilder()
            .withName("lookup")
            .withDescription("Lookup info")
            .withExecutor(args -> ToolResult.success("result"))
            .build();

    var memory = InMemoryMemory.withDefaults();
    var session =
        SessionContext.newBuilder().withUserInput("Find info").withUserId("user-1").build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(lookupTool)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals("Found it", ((StreamEvent.Done) events.getLast()).response().content());
    var history = memory.history("user-1", session.sessionId());
    assertTrue(history.size() >= 3);
  }

  @Test
  void streamUnknownToolWithTracing() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("nonexistent")
                      .withArguments(Map.of())
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
                        new StreamEvent.TextDelta("Recovered"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Recovered")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    var unknownToolSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .filter(s -> "nonexistent".equals(s.attributes().get("toolName")))
            .findFirst();
    assertTrue(unknownToolSpan.isPresent());
    assertFalse(unknownToolSpan.get().success());
  }

  @Test
  void streamVerboseTracingNullToolArguments() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc = new ToolCall("call_1", "simple", null);
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
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Done")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var simpleTool =
        Tool.newBuilder()
            .withName("simple")
            .withDescription("Simple")
            .withExecutor(args -> ToolResult.success("ok"))
            .build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(simpleTool)
                .withTraceListener(traces::add)
                .withTraceDetail(ai.singlr.core.trace.TraceDetail.VERBOSE)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
    assertEquals(1, traces.size());
    var toolSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .findFirst()
            .orElseThrow();
    assertFalse(toolSpan.attributes().containsKey("arguments"));
  }

  @Test
  void streamInterruptedDuringPut() throws Exception {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            Thread.currentThread().interrupt();
            return CloseableIterator.of(
                List.<StreamEvent>of(
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    var last = events.getLast();
    assertInstanceOf(StreamEvent.Error.class, last);
    assertTrue(((StreamEvent.Error) last).message().contains("terminated unexpectedly"));
  }

  @Test
  void streamMemoryWithNullSessionId() {
    var model =
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
                        new StreamEvent.TextDelta("ok"),
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("ok")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var memory = InMemoryMemory.withDefaults();
    var session = new SessionContext("user-1", null, "Hello", Map.of(), Map.of(), List.of());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamMemoryNullSessionIdWithToolCalls() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              var tc =
                  ToolCall.newBuilder()
                      .withId("call_1")
                      .withName("tool_a")
                      .withArguments(Map.of())
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
                        new StreamEvent.Done(
                            Response.newBuilder()
                                .withContent("Done")
                                .withFinishReason(FinishReason.STOP)
                                .build()))
                    .iterator());
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

    var toolA =
        Tool.newBuilder()
            .withName("tool_a")
            .withDescription("A")
            .withExecutor(args -> ToolResult.success("Result"))
            .build();

    var memory = InMemoryMemory.withDefaults();
    var session = new SessionContext("user-1", null, "Test", Map.of(), Map.of(), List.of());

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(toolA)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream(session));

    assertInstanceOf(StreamEvent.Done.class, events.getLast());
  }

  @Test
  void streamToolTimeoutWithTracing() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public CloseableIterator<StreamEvent> chatStream(
              List<Message> messages, List<Tool> tools) {
            var tc =
                ToolCall.newBuilder()
                    .withId("call_1")
                    .withName("slow_tool")
                    .withArguments(Map.of())
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

          @Override
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var slowTool =
        Tool.newBuilder()
            .withName("slow_tool")
            .withDescription("Slow")
            .withExecutor(
                args -> {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return ToolResult.success("done");
                })
            .build();

    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(50)).build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(slowTool)
                .withFaultTolerance(ft)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var events = collectAll(agent.runStream("Test"));

    assertInstanceOf(StreamEvent.Error.class, events.getLast());
    assertEquals(1, traces.size());
    assertFalse(traces.getFirst().success());

    var toolSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .findFirst();
    assertTrue(toolSpan.isPresent());
    assertFalse(toolSpan.get().success());
  }

  @Test
  void iteratorPollTimeoutThenReceive() throws Exception {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var keepAlive = new CountDownLatch(1);
    var thread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    keepAlive.await();
                  } catch (InterruptedException e) {
                    /* expected */
                  }
                });

    var iterator = new AgentStreamIterator(queue, thread);

    // hasNext() blocks in a poll loop (100ms per cycle). Run it on a background thread
    // so the main thread can control exactly when the event arrives.
    var hasNextResult = new AtomicBoolean(false);
    var callingHasNext = new CountDownLatch(1);
    var hasNextDone = new CountDownLatch(1);
    Thread.ofVirtual()
        .start(
            () -> {
              callingHasNext.countDown();
              hasNextResult.set(iterator.hasNext());
              hasNextDone.countDown();
            });

    // Wait for the hasNext thread to enter the poll loop, then let at least one
    // poll timeout (100ms) elapse so the loopThread.isAlive() branch is exercised.
    callingHasNext.await(5, TimeUnit.SECONDS);
    Thread.sleep(250);

    // Now deliver the event — the next poll cycle picks it up.
    queue.add(new StreamEvent.TextDelta("delayed"));
    assertTrue(hasNextDone.await(5, TimeUnit.SECONDS));
    assertTrue(hasNextResult.get());

    var event = iterator.next();
    assertInstanceOf(StreamEvent.TextDelta.class, event);
    assertEquals("delayed", ((StreamEvent.TextDelta) event).text());

    queue.add(
        new StreamEvent.Done(
            Response.newBuilder().withContent("ok").withFinishReason(FinishReason.STOP).build()));
    assertTrue(iterator.hasNext());
    iterator.next();
    assertFalse(iterator.hasNext());

    iterator.close();
    keepAlive.countDown();
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
