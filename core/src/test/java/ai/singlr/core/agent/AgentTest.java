/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import ai.singlr.core.fault.Backoff;
import ai.singlr.core.fault.FaultTolerance;
import ai.singlr.core.fault.RetryPolicy;
import ai.singlr.core.memory.InMemoryMemory;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.test.MockModel;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.core.trace.SpanKind;
import ai.singlr.core.trace.Trace;
import ai.singlr.core.trace.TraceDetail;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentTest {

  private record Weather(String city, int temperature) {}

  @Test
  void simpleConversation() {
    var model = new MockModel("Hello! How can I help you?");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TestAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Hi there!");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertEquals("Hello! How can I help you?", response.content());
  }

  @Test
  void withMemory() {
    var model = new MockModel("I remember you're Alice!");
    var memory = InMemoryMemory.newBuilder().withBlock("user", "User information").build();
    memory.updateBlock("user", "name", "Alice");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("MemoryAgent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Who am I?");

    assertTrue(result.isSuccess());
    assertTrue(model.lastMessages().getFirst().content().contains("name: Alice"));
  }

  @Test
  void withToolCall() {
    var callCount = new AtomicInteger(0);

    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("get_time")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            } else {
              return Response.newBuilder()
                  .withContent("The current time is 10:30 AM")
                  .withFinishReason(FinishReason.STOP)
                  .build();
            }
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
                .withName("ToolAgent")
                .withModel(model)
                .withTool(timeTool)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("What time is it?");

    assertTrue(result.isSuccess());
    assertEquals(2, callCount.get());
    var response = ((Result.Success<Response>) result).value();
    assertEquals("The current time is 10:30 AM", response.content());
  }

  @Test
  void maxIterationsReached() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withToolCalls(
                    List.of(
                        ToolCall.newBuilder()
                            .withId("call_" + System.nanoTime())
                            .withName("loop")
                            .withArguments(Map.of())
                            .build()))
                .withFinishReason(FinishReason.TOOL_CALLS)
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

    var loopTool =
        Tool.newBuilder()
            .withName("loop")
            .withDescription("Loops forever")
            .withExecutor(args -> ToolResult.success("Continue"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("LoopAgent")
                .withModel(model)
                .withTool(loopTool)
                .withMaxIterations(3)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Start loop");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertTrue(failure.error().contains("Max iterations"));
  }

  @Test
  void stepBasedExecution() {
    var model = new MockModel("Step response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("StepAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var state = agent.initialState("Hello", Map.of());
    assertFalse(state.isComplete());
    assertEquals(0, state.iterations());

    var result = agent.step(state);
    assertTrue(result.isSuccess());

    var newState = ((Result.Success<AgentState>) result).value();
    assertTrue(newState.isComplete());
    assertEquals(1, newState.iterations());
    assertEquals("Step response", newState.finalResponse().content());
  }

  @Test
  void memoryToolsIncludedInModelCall() {
    var receivedTools = new ArrayList<Tool>();
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            receivedTools.addAll(tools);
            return Response.newBuilder()
                .withContent("OK")
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
    var memory = InMemoryMemory.withDefaults();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("MemToolAgent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(true)
                .build());

    agent.run("Test");

    assertTrue(receivedTools.stream().anyMatch(t -> t.name().equals("memory_update")));
    assertTrue(receivedTools.stream().anyMatch(t -> t.name().equals("memory_read")));
    assertEquals(2, receivedTools.stream().filter(t -> t.name().startsWith("memory_")).count());
  }

  @Test
  void customSystemPrompt() {
    var model = new MockModel("Custom response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("CustomAgent")
                .withModel(model)
                .withSystemPrompt("You are {name}. Be brief.")
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Test");

    var systemMsg = model.lastMessages().getFirst();
    assertEquals("You are CustomAgent. Be brief.", systemMsg.content());
  }

  @Test
  void promptVariables() {
    var model = new MockModel("Response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("VarAgent")
                .withModel(model)
                .withSystemPrompt("{name} in {mode} mode")
                .withIncludeMemoryTools(false)
                .build());

    agent.run(SessionContext.of("Test", Map.of("mode", "debug")));

    var systemMsg = model.lastMessages().getFirst();
    assertEquals("VarAgent in debug mode", systemMsg.content());
  }

  @Test
  void stepOnCompleteState() {
    var model = new MockModel("Response");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var completeState = AgentState.newBuilder().withComplete(true).build();
    var result = agent.step(completeState);

    assertTrue(result.isSuccess());
    var state = ((Result.Success<AgentState>) result).value();
    assertTrue(state.isComplete());
  }

  @Test
  void unknownToolCall() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("unknown_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
  }

  @Test
  void modelException() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
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

    var result = agent.run("Test");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertTrue(failure.error().contains("Agent step failed"));
  }

  @Test
  void configGetter() {
    var model = new MockModel("Response");
    var config =
        AgentConfig.newBuilder()
            .withName("TestAgent")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build();
    var agent = new Agent(config);

    assertEquals(config, agent.config());
    assertEquals("TestAgent", agent.config().name());
  }

  @Test
  void memoryStoresToolMessages() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("test_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var memory = InMemoryMemory.withDefaults();
    var tool =
        Tool.newBuilder()
            .withName("test_tool")
            .withDescription("Test")
            .withExecutor(args -> ToolResult.success("Tool result"))
            .build();
    var session = SessionContext.of("Test");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withTool(tool)
                .withIncludeMemoryTools(false)
                .build());

    agent.run(session);

    var history = memory.history(null, session.sessionId());
    assertTrue(history.size() >= 3);
    assertTrue(history.stream().anyMatch(m -> m.role() == ai.singlr.core.model.Role.TOOL));
  }

  @Test
  void memoryHistoryIncludedInMessages() {
    var model = new MockModel("Response");
    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.of("New message");
    memory.addMessage(null, session.sessionId(), Message.user("Previous message"));
    memory.addMessage(null, session.sessionId(), Message.assistant("Previous response"));

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    agent.run(session);

    assertTrue(model.lastMessages().size() >= 4);
  }

  @Test
  void tracingEmitsTraceOnSuccess() {
    var model = new MockModel("Hello!");
    var received = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TracedAgent")
                .withModel(model)
                .withTraceListener(received::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Hi");

    assertTrue(result.isSuccess());
    assertEquals(1, received.size());
    var trace = received.getFirst();
    assertEquals("TracedAgent", trace.name());
    assertTrue(trace.success());
    assertEquals(1, trace.spans().size());
    assertEquals(SpanKind.MODEL_CALL, trace.spans().getFirst().kind());
    assertEquals("model.chat", trace.spans().getFirst().name());
    assertEquals("mock", trace.spans().getFirst().attributes().get("model"));
  }

  @Test
  void tracingEmitsTraceOnFailure() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
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

    var received = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TracedAgent")
                .withModel(model)
                .withTraceListener(received::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isFailure());
    assertEquals(1, received.size());
    assertFalse(received.getFirst().success());
  }

  @Test
  void tracingEmitsToolExecutionSpans() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("get_time")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var timeTool =
        Tool.newBuilder()
            .withName("get_time")
            .withDescription("Get current time")
            .withExecutor(args -> ToolResult.success("10:30 AM"))
            .build();

    var received = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("TracedAgent")
                .withModel(model)
                .withTool(timeTool)
                .withTraceListener(received::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("What time?");

    assertTrue(result.isSuccess());
    assertEquals(1, received.size());
    var trace = received.getFirst();
    assertTrue(trace.success());
    assertEquals(3, trace.spans().size());
    assertEquals(SpanKind.MODEL_CALL, trace.spans().get(0).kind());
    assertEquals(SpanKind.TOOL_EXECUTION, trace.spans().get(1).kind());
    assertEquals("get_time", trace.spans().get(1).attributes().get("toolName"));
    assertEquals(SpanKind.MODEL_CALL, trace.spans().get(2).kind());
  }

  @Test
  void faultToleranceRetriesModelCall() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() < 2) {
              throw new RuntimeException("Transient error");
            }
            return Response.newBuilder()
                .withContent("Recovered")
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

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    assertEquals(3, callCount.get());
    assertEquals("Recovered", ((Result.Success<Response>) result).value().content());
  }

  @Test
  void faultToleranceExhaustedReturnsFailure() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("Permanent error");
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
                    .withMaxAttempts(2)
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

    var result = agent.run("Test");

    assertTrue(result.isFailure());
  }

  @Test
  void noTracingWhenNoListeners() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Hi");

    assertTrue(result.isSuccess());
  }

  @Test
  void structuredOutput() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            var parsed = outputSchema.type().cast(new Weather("London", 18));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(SessionContext.of("Weather in London"), OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertTrue(response.hasParsed());
    assertEquals("London", response.parsed().city());
    assertEquals(18, response.parsed().temperature());
  }

  @Test
  void structuredOutputWithToolCalls() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            if (callCount.getAndIncrement() == 0) {
              return Response.<T>newBuilder(outputSchema.type())
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("get_weather")
                              .withArguments(Map.of("city", "Tokyo"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            var parsed = outputSchema.type().cast(new Weather("Tokyo", 25));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Get weather")
            .withExecutor(args -> ToolResult.success("25°C"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(weatherTool)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(SessionContext.of("Weather in Tokyo"), OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess());
    assertEquals(2, callCount.get());
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertEquals("Tokyo", response.parsed().city());
    assertEquals(25, response.parsed().temperature());
  }

  @Test
  void stepWithStructuredOutput() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            var parsed = outputSchema.type().cast(new Weather("Paris", 22));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var schema = OutputSchema.of(Weather.class);
    var state = agent.initialState("Weather in Paris", Map.of());
    var result = agent.step(state, schema);

    assertTrue(result.isSuccess());
    var newState = ((Result.Success<AgentState>) result).value();
    assertTrue(newState.isComplete());
    assertTrue(newState.finalResponse().hasParsed());
    var weather = (Weather) newState.finalResponse().parsed();
    assertEquals("Paris", weather.city());
    assertEquals(22, weather.temperature());
  }

  @Test
  void structuredOutputWithFaultTolerance() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            if (callCount.getAndIncrement() < 2) {
              throw new RuntimeException("Transient error");
            }
            var parsed = outputSchema.type().cast(new Weather("Tokyo", 25));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var result = agent.run(SessionContext.of("Weather in Tokyo"), OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess());
    assertEquals(3, callCount.get());
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertEquals("Tokyo", response.parsed().city());
    assertEquals(25, response.parsed().temperature());
  }

  @Test
  void structuredOutputUnsupportedModel() {
    var model = new MockModel("plain text");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(SessionContext.of("Test"), OutputSchema.of(Weather.class));

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response<Weather>>) result;
    assertTrue(failure.error().contains("Agent step failed"));
  }

  @Test
  void sessionAwareStructuredOutput() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            var parsed = outputSchema.type().cast(new Weather("Berlin", 15));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.of("Weather in Berlin");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(session, OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertEquals("Berlin", response.parsed().city());
    assertEquals(15, response.parsed().temperature());

    var history = memory.history(null, session.sessionId());
    assertFalse(history.isEmpty());
  }

  @Test
  void sessionAwareStructuredOutputWithPromptVars() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new UnsupportedOperationException();
          }

          @Override
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            var parsed = outputSchema.type().cast(new Weather("Rome", 28));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
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

    var memory = InMemoryMemory.withDefaults();
    var session =
        SessionContext.newBuilder()
            .withUserInput("Weather in Rome")
            .withPromptVars(Map.of("mode", "debug"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withSystemPrompt("{name} in {mode} mode")
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(session, OutputSchema.of(Weather.class));

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response<Weather>>) result).value();
    assertEquals("Rome", response.parsed().city());
  }

  @Test
  void nullInputReturnsFailure() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run((String) null);

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertEquals("userInput must not be null or blank", failure.error());
  }

  @Test
  void blankInputReturnsFailure() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("   ");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertEquals("userInput must not be null or blank", failure.error());
  }

  @Test
  void sessionWithNoMemory() {
    var model = new MockModel("Hello!");
    var session = SessionContext.of("Test");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run(session);

    assertTrue(result.isSuccess());
  }

  @Test
  void defaultFaultToleranceIsNonNull() {
    var model = new MockModel("Hello!");
    var config = AgentConfig.newBuilder().withName("Agent").withModel(model).build();

    assertEquals(FaultTolerance.PASSTHROUGH, config.faultTolerance());
  }

  @Test
  void runWithUserIdRegistersSession() {
    var model = new MockModel("Hello!");
    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.newBuilder().withUserId("alice").withUserInput("Hi there").build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    agent.run(session);

    var latest = memory.latestSession("alice");
    assertTrue(latest.isPresent());
    assertEquals(session.sessionId(), latest.get());
    assertEquals(1, memory.sessions("alice").size());
  }

  @Test
  void runWithoutUserIdDoesNotRegister() {
    var model = new MockModel("Hello!");
    var memory = InMemoryMemory.withDefaults();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Hi there");

    assertTrue(memory.sessions("anything").isEmpty());
  }

  @Test
  void nullSessionReturnsFailure() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run((SessionContext) null);

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertEquals("session must not be null", failure.error());
  }

  @Test
  void tracingWithUsageStats() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("Done")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Response.Usage.of(100, 50))
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Hello");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var span = traces.getFirst().spans().getFirst();
    assertEquals("100", span.attributes().get("inputTokens"));
    assertEquals("50", span.attributes().get("outputTokens"));
  }

  @Test
  void tracingWithUnknownToolCall() {
    var model =
        new Model() {
          private int callCount = 0;

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (callCount++ == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("nonexistent_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Call a tool");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var toolSpan = traces.getFirst().spans().get(1);
    assertEquals(SpanKind.TOOL_EXECUTION, toolSpan.kind());
    assertFalse(toolSpan.success());
  }

  @Test
  void tracingWithToolFailure() {
    var model =
        new Model() {
          private int callCount = 0;

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (callCount++ == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("failing_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var failingTool =
        Tool.newBuilder()
            .withName("failing_tool")
            .withDescription("A tool that fails")
            .withExecutor(args -> ToolResult.failure("tool error"))
            .build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(failingTool)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Call a tool");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var toolSpan = traces.getFirst().spans().get(1);
    assertEquals(SpanKind.TOOL_EXECUTION, toolSpan.kind());
    assertFalse(toolSpan.success());
  }

  @Test
  void stepOnCompleteStateReturnsImmediately() {
    var model = new MockModel("Hello!");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var completeState =
        AgentState.newBuilder()
            .withMessages(List.of(Message.system("sys"), Message.user("hi")))
            .withComplete(true)
            .build();

    var result = agent.step(completeState);

    assertTrue(result.isSuccess());
    var state = ((Result.Success<AgentState>) result).value();
    assertTrue(state.isComplete());
  }

  @Test
  void standardTracingCapturesFinishReason() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("Done")
                .withFinishReason(FinishReason.STOP)
                .withUsage(Response.Usage.of(10, 20))
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Hello");

    var span = traces.getFirst().spans().getFirst();
    assertEquals("STOP", span.attributes().get("finishReason"));
  }

  @Test
  void standardTracingOmitsToolArgsAndResults() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("search")
                              .withArguments(Map.of("query", "weather"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Weather?");

    var toolSpan = traces.getFirst().spans().get(1);
    assertEquals("search", toolSpan.attributes().get("toolName"));
    assertFalse(toolSpan.attributes().containsKey("arguments"));
    assertFalse(toolSpan.attributes().containsKey("result"));
  }

  @Test
  void verboseTracingCapturesToolArgsAndResults() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("search")
                              .withArguments(Map.of("query", "weather"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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
                .withTraceDetail(TraceDetail.VERBOSE)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("Weather?");

    var toolSpan = traces.getFirst().spans().get(1);
    assertTrue(toolSpan.attributes().containsKey("arguments"));
    assertTrue(toolSpan.attributes().get("arguments").contains("weather"));
    assertEquals("sunny 25C", toolSpan.attributes().get("result"));
  }

  @Test
  void verboseTracingCapturesThinking() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("The answer is 42")
                .withThinking("Let me reason about this step by step...")
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withTraceDetail(TraceDetail.VERBOSE)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("What is the answer?");

    var span = traces.getFirst().spans().getFirst();
    assertEquals("Let me reason about this step by step...", span.attributes().get("thinking"));
  }

  @Test
  void standardTracingOmitsThinking() {
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("The answer is 42")
                .withThinking("Let me reason about this step by step...")
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTraceListener(traces::add)
                .withIncludeMemoryTools(false)
                .build());

    agent.run("What is the answer?");

    var span = traces.getFirst().spans().getFirst();
    assertFalse(span.attributes().containsKey("thinking"));
  }

  @Test
  void inlineFilesPassedToModel() {
    var capturedMessages = new ArrayList<List<Message>>();
    var model =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            capturedMessages.add(List.copyOf(messages));
            return Response.newBuilder()
                .withContent("I see an image")
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

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("VisionAgent")
                .withModel(model)
                .withIncludeMemoryTools(false)
                .build());

    var session =
        SessionContext.newBuilder()
            .withUserInput("Describe this")
            .withInlineFile(new byte[] {1, 2, 3}, "image/png")
            .build();

    var result = agent.run(session);

    assertTrue(result.isSuccess());
    var messages = capturedMessages.getFirst();
    var userMsg = messages.get(messages.size() - 1);
    assertEquals("Describe this", userMsg.content());
    assertTrue(userMsg.hasInlineFiles());
    assertEquals("image/png", userMsg.inlineFiles().getFirst().mimeType());
  }

  @Test
  void inlineFilesStrippedFromMemory() {
    var memory = InMemoryMemory.newBuilder().withBlock("user", "User info").build();
    var model = new MockModel("I see the file");
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withMemory(memory)
                .withIncludeMemoryTools(false)
                .build());

    var session =
        SessionContext.newBuilder()
            .withUserInput("Extract text from PDF")
            .withInlineFile(new byte[] {0x50, 0x44, 0x46}, "application/pdf")
            .build();

    var result = agent.run(session);

    assertTrue(result.isSuccess());
    var history = memory.history(null, session.sessionId());
    for (var msg : history) {
      assertFalse(msg.hasInlineFiles(), "Memory should not contain inline files");
    }
  }

  // --- Parallel tool execution ---

  @Test
  void parallelToolExecution() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("tool_a")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("tool_b")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Both tools executed")
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
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    assertEquals(2, callCount.get());
    assertEquals("Both tools executed", ((Result.Success<Response>) result).value().content());
  }

  @Test
  void parallelToolExecutionPreservesOrder() {
    var latch = new CountDownLatch(1);
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("slow_tool")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("fast_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            var toolMsgs =
                messages.stream().filter(m -> m.role() == ai.singlr.core.model.Role.TOOL).toList();
            var firstToolContent = toolMsgs.get(toolMsgs.size() - 2).content();
            return Response.newBuilder()
                .withContent("First tool: " + firstToolContent)
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

    var slowTool =
        Tool.newBuilder()
            .withName("slow_tool")
            .withDescription("Slow")
            .withExecutor(
                args -> {
                  try {
                    latch.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return ToolResult.success("Slow result");
                })
            .build();
    var fastTool =
        Tool.newBuilder()
            .withName("fast_tool")
            .withDescription("Fast")
            .withExecutor(
                args -> {
                  latch.countDown();
                  return ToolResult.success("Fast result");
                })
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(slowTool)
                .withTool(fastTool)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    var content = ((Result.Success<Response>) result).value().content();
    assertTrue(content.contains("Slow result"));
  }

  @Test
  void parallelToolExecutionOneFailure() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("good_tool")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("bad_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Handled failure")
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

    var goodTool =
        Tool.newBuilder()
            .withName("good_tool")
            .withDescription("Good")
            .withExecutor(args -> ToolResult.success("Success"))
            .build();
    var badTool =
        Tool.newBuilder()
            .withName("bad_tool")
            .withDescription("Bad")
            .withExecutor(args -> ToolResult.failure("Tool error"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(goodTool)
                .withTool(badTool)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    assertEquals("Handled failure", ((Result.Success<Response>) result).value().content());
  }

  @Test
  void parallelToolExecutionUnknownTool() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("unknown_tool")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("known_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var knownTool =
        Tool.newBuilder()
            .withName("known_tool")
            .withDescription("Known")
            .withExecutor(args -> ToolResult.success("Known result"))
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(knownTool)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
  }

  @Test
  void parallelToolExecutionWithFaultTolerance() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("slow_tool")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("fast_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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
    var fastTool =
        Tool.newBuilder()
            .withName("fast_tool")
            .withDescription("Fast")
            .withExecutor(args -> ToolResult.success("Fast result"))
            .build();

    var ft = FaultTolerance.newBuilder().withOperationTimeout(Duration.ofMillis(100)).build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(slowTool)
                .withTool(fastTool)
                .withFaultTolerance(ft)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
  }

  @Test
  void parallelToolExecutionSingleTool() {
    var callCount = new AtomicInteger(0);
    var executionThreads = new ConcurrentHashMap<String, String>();
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("single_tool")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var singleTool =
        Tool.newBuilder()
            .withName("single_tool")
            .withDescription("Single")
            .withExecutor(
                args -> {
                  executionThreads.put("tool", Thread.currentThread().getName());
                  return ToolResult.success("Result");
                })
            .build();

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(singleTool)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
  }

  @Test
  void parallelToolExecutionDisabledByDefault() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("tool_a")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("tool_b")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var toolA =
        Tool.newBuilder()
            .withName("tool_a")
            .withDescription("A")
            .withExecutor(args -> ToolResult.success("A"))
            .build();
    var toolB =
        Tool.newBuilder()
            .withName("tool_b")
            .withDescription("B")
            .withExecutor(args -> ToolResult.success("B"))
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

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    assertFalse(agent.config().parallelToolExecution());
  }

  @Test
  void parallelToolExecutionWithMemory() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("tool_a")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("tool_b")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.of("Test");

    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(toolA)
                .withTool(toolB)
                .withMemory(memory)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    agent.run(session);

    var history = memory.history(null, session.sessionId());
    var toolMessages =
        history.stream().filter(m -> m.role() == ai.singlr.core.model.Role.TOOL).toList();
    assertEquals(2, toolMessages.size());
  }

  @Test
  void parallelToolExecutionWithTracing() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("tool_a")
                              .withArguments(Map.of())
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("tool_b")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(toolA)
                .withTool(toolB)
                .withTraceListener(traces::add)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    assertEquals(1, traces.size());
    var toolSpans =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .toList();
    assertEquals(2, toolSpans.size());
    assertTrue(toolSpans.stream().anyMatch(s -> "tool_a".equals(s.attributes().get("toolName"))));
    assertTrue(toolSpans.stream().anyMatch(s -> "tool_b".equals(s.attributes().get("toolName"))));
  }

  @Test
  void parallelToolExecutionWithVerboseTracing() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            if (callCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("search")
                              .withArguments(Map.of("q", "weather"))
                              .build(),
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("lookup")
                              .withArguments(Map.of("id", "123"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done")
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

    var searchTool =
        Tool.newBuilder()
            .withName("search")
            .withDescription("Search")
            .withExecutor(args -> ToolResult.success("sunny"))
            .build();
    var lookupTool =
        Tool.newBuilder()
            .withName("lookup")
            .withDescription("Lookup")
            .withExecutor(args -> ToolResult.success("found"))
            .build();

    var traces = new ArrayList<Trace>();
    var agent =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Agent")
                .withModel(model)
                .withTool(searchTool)
                .withTool(lookupTool)
                .withTraceListener(traces::add)
                .withTraceDetail(TraceDetail.VERBOSE)
                .withParallelToolExecution(true)
                .withIncludeMemoryTools(false)
                .build());

    var result = agent.run("Test");

    assertTrue(result.isSuccess());
    var toolSpans =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .toList();
    for (var span : toolSpans) {
      assertTrue(span.attributes().containsKey("arguments"));
      assertTrue(span.attributes().containsKey("result"));
    }
  }

  // --- Agent.asTool() ---

  @Test
  void asToolSimple() {
    var model = new MockModel("Sub-agent response");
    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build();

    var tool = Agent.asTool("sub_agent", "A sub-agent", config);

    assertEquals("sub_agent", tool.name());
    assertEquals("A sub-agent", tool.description());
    var result = tool.execute(Map.of("task", "Do something"));
    assertTrue(result.success());
    assertEquals("Sub-agent response", result.output());
  }

  @Test
  void asToolFailure() {
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("Sub-agent error");
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

    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build();

    var tool = Agent.asTool("sub_agent", "A sub-agent", config);
    var result = tool.execute(Map.of("task", "Do something"));

    assertFalse(result.success());
    assertTrue(result.output().contains("Agent step failed"));
  }

  @Test
  void asToolFreshAgentPerCall() {
    var callCount = new AtomicInteger(0);
    var model =
        new Model() {
          @Override
          public Response chat(List<Message> messages, List<Tool> tools) {
            callCount.incrementAndGet();
            assertEquals(2, messages.size());
            return Response.newBuilder()
                .withContent("Response " + callCount.get())
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

    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(model)
            .withIncludeMemoryTools(false)
            .build();

    var tool = Agent.asTool("sub_agent", "A sub-agent", config);

    var result1 = tool.execute(Map.of("task", "First task"));
    var result2 = tool.execute(Map.of("task", "Second task"));

    assertTrue(result1.success());
    assertTrue(result2.success());
    assertEquals(2, callCount.get());
  }

  @Test
  void asToolNullNameThrows() {
    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(new MockModel("ok"))
            .withIncludeMemoryTools(false)
            .build();

    assertThrows(IllegalArgumentException.class, () -> Agent.asTool(null, "desc", config));
  }

  @Test
  void asToolBlankNameThrows() {
    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(new MockModel("ok"))
            .withIncludeMemoryTools(false)
            .build();

    assertThrows(IllegalArgumentException.class, () -> Agent.asTool("  ", "desc", config));
  }

  @Test
  void asToolNullConfigThrows() {
    assertThrows(IllegalArgumentException.class, () -> Agent.asTool("name", "desc", null));
  }

  @Test
  void asToolNullDescription() {
    var config =
        AgentConfig.newBuilder()
            .withName("SubAgent")
            .withModel(new MockModel("ok"))
            .withIncludeMemoryTools(false)
            .build();

    var tool = Agent.asTool("sub_agent", null, config);

    assertEquals("sub_agent", tool.description());
  }
}
