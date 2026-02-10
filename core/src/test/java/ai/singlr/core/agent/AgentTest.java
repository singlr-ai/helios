/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    assertTrue(receivedTools.stream().anyMatch(t -> t.name().equals("core_memory_update")));
    assertTrue(receivedTools.stream().anyMatch(t -> t.name().equals("archival_memory_insert")));
    assertTrue(receivedTools.stream().anyMatch(t -> t.name().equals("conversation_search")));
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

    var history = memory.history(session.sessionId());
    assertTrue(history.size() >= 3);
    assertTrue(history.stream().anyMatch(m -> m.role() == ai.singlr.core.model.Role.TOOL));
  }

  @Test
  void memoryHistoryIncludedInMessages() {
    var model = new MockModel("Response");
    var memory = InMemoryMemory.withDefaults();
    var session = SessionContext.of("New message");
    memory.addMessage(session.sessionId(), Message.user("Previous message"));
    memory.addMessage(session.sessionId(), Message.assistant("Previous response"));

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

    var history = memory.history(session.sessionId());
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
}
