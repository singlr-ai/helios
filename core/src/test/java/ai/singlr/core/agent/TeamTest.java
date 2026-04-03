/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import ai.singlr.core.fault.FaultTolerance;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TeamTest {

  private record Summary(String title, String body) {}

  @Test
  void singleDelegation() {
    var workerModel = new MockModel("Research findings: Java virtual threads use M:N scheduling.");
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
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("researcher")
                              .withArguments(Map.of("task", "Research Java virtual threads"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Blog post about virtual threads")
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withSystemPrompt("You lead a content team.")
            .withWorker("researcher", "Finds and synthesizes information", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Write about Java virtual threads");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertEquals("Blog post about virtual threads", response.content());
    assertEquals(2, leaderCallCount.get());
  }

  @Test
  void multiWorkerDelegation() {
    var researcherModel = new MockModel("Key facts about virtual threads.");
    var researcher =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(researcherModel)
                .withIncludeMemoryTools(false)
                .build());

    var writerModel = new MockModel("Polished blog post content.");
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
            var turn = leaderCallCount.getAndIncrement();
            if (turn == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("researcher")
                              .withArguments(Map.of("task", "Research virtual threads"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            if (turn == 1) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("writer")
                              .withArguments(Map.of("task", "Write blog post from research"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Final published blog post")
                .withFinishReason(FinishReason.STOP)
                .build();
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

    var result = team.run("Write a blog post");

    assertTrue(result.isSuccess());
    assertEquals(
        "Final published blog post", ((Result.Success<Response>) result).value().content());
    assertEquals(3, leaderCallCount.get());
  }

  @Test
  void workerFailure() {
    var failingModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            throw new RuntimeException("Worker model unavailable");
          }

          @Override
          public String id() {
            return "failing-mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Researcher")
                .withModel(failingModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("researcher")
                              .withArguments(Map.of("task", "Research something"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("I could not reach the researcher, but here is my best answer.")
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withWorker("researcher", "Finds information", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Research something");

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response>) result).value();
    assertTrue(response.content().contains("could not reach"));
  }

  @Test
  void leaderDirectTools() {
    var workerModel = new MockModel("Draft content.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Writer")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var publishCalled = new AtomicInteger(0);
    var publishTool =
        Tool.newBuilder()
            .withName("publish")
            .withDescription("Publish content to the blog")
            .withExecutor(
                args -> {
                  publishCalled.incrementAndGet();
                  return ToolResult.success("Published successfully");
                })
            .build();

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var turn = leaderCallCount.getAndIncrement();
            if (turn == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("writer")
                              .withArguments(Map.of("task", "Write a post"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            if (turn == 1) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_2")
                              .withName("publish")
                              .withArguments(Map.of())
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Post written and published!")
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withWorker("writer", "Writes content", worker)
            .withTool(publishTool)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Write and publish a post");

    assertTrue(result.isSuccess());
    assertEquals(1, publishCalled.get());
    assertEquals(
        "Post written and published!", ((Result.Success<Response>) result).value().content());
  }

  @Test
  void maxIterationsGuard() {
    var workerModel = new MockModel("Worker result");
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
            return Response.newBuilder()
                .withToolCalls(
                    List.of(
                        ToolCall.newBuilder()
                            .withId("call_" + System.nanoTime())
                            .withName("worker")
                            .withArguments(Map.of("task", "Do something"))
                            .build()))
                .withFinishReason(FinishReason.TOOL_CALLS)
                .build();
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
            .withMaxIterations(3)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Loop forever");

    assertTrue(result.isFailure());
    var failure = (Result.Failure<Response>) result;
    assertTrue(failure.error().contains("Max iterations"));
  }

  @Test
  void structuredOutput() {
    var workerModel = new MockModel("Research: Virtual threads are lightweight.");
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
          public <T> Response<T> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<T> outputSchema) {
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.<T>newBuilder(outputSchema.type())
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("researcher")
                              .withArguments(Map.of("task", "Research virtual threads"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            var parsed =
                outputSchema.type().cast(new Summary("Virtual Threads", "They are lightweight."));
            return Response.<T>newBuilder(outputSchema.type())
                .withParsed(parsed)
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withWorker("researcher", "Finds information", worker)
            .withIncludeMemoryTools(false)
            .build();

    var result =
        team.run(SessionContext.of("Summarize virtual threads"), OutputSchema.of(Summary.class));

    assertTrue(result.isSuccess());
    var response = ((Result.Success<Response<Summary>>) result).value();
    assertTrue(response.hasParsed());
    assertEquals("Virtual Threads", response.parsed().title());
    assertEquals("They are lightweight.", response.parsed().body());
  }

  @Test
  void workerNameCollisionThrows() {
    var workerModel = new MockModel("Worker result");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var conflictingTool =
        Tool.newBuilder()
            .withName("researcher")
            .withDescription("Conflicts with worker name")
            .withExecutor(args -> ToolResult.success("result"))
            .build();

    var builder =
        Team.newBuilder()
            .withName("team")
            .withModel(new MockModel("ok"))
            .withWorker("researcher", "Finds information", worker)
            .withTool(conflictingTool);

    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertTrue(ex.getMessage().contains("researcher"));
    assertTrue(ex.getMessage().contains("conflicts"));
  }

  @Test
  void noWorkersThrows() {
    var builder = Team.newBuilder().withName("team").withModel(new MockModel("ok"));

    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertTrue(ex.getMessage().contains("worker"));
  }

  @Test
  void noModelThrows() {
    var workerModel = new MockModel("Worker result");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var builder = Team.newBuilder().withName("team").withWorker("worker", "Does work", worker);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  void systemPromptIncludesWorkers() {
    var workerModel = new MockModel("Worker result");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var capturedMessages = new ArrayList<List<Message>>();
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            capturedMessages.add(List.copyOf(messages));
            return Response.newBuilder()
                .withContent("Done")
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withSystemPrompt("You are the leader.")
            .withWorker("researcher", "Finds and synthesizes information", worker)
            .withWorker("writer", "Writes polished content", worker)
            .withIncludeMemoryTools(false)
            .build();

    team.run("Test");

    assertFalse(capturedMessages.isEmpty());
    var systemMsg = capturedMessages.getFirst().getFirst().content();
    assertTrue(systemMsg.contains("You are the leader."));
    assertTrue(systemMsg.contains("## Team Members"));
    assertTrue(systemMsg.contains("researcher: Finds and synthesizes information"));
    assertTrue(systemMsg.contains("writer: Writes polished content"));
    assertTrue(systemMsg.contains("Delegate to the right specialist"));
  }

  @Test
  void tracing() {
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
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("researcher")
                              .withArguments(Map.of("task", "Find facts"))
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

    var result = team.run("Research something");

    assertTrue(result.isSuccess());
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
  void accessors() {
    var workerModel = new MockModel("Result");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var team =
        Team.newBuilder()
            .withName("my-team")
            .withModel(new MockModel("ok"))
            .withWorker("worker", "Does work", worker)
            .withIncludeMemoryTools(false)
            .build();

    assertEquals("my-team", team.name());
    assertNotNull(team.leader());
    assertEquals(1, team.workers().size());
    assertTrue(team.workers().containsKey("worker"));
  }

  @Test
  void sessionContextRun() {
    var workerModel = new MockModel("Worker output.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("worker")
                              .withArguments(Map.of("task", "Do work"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Session result")
                .withFinishReason(FinishReason.STOP)
                .build();
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

    var result = team.run(SessionContext.of("Session input"));

    assertTrue(result.isSuccess());
    assertEquals("Session result", ((Result.Success<Response>) result).value().content());
  }

  @Test
  void maxIterationsZeroThrows() {
    var workerModel = new MockModel("Result");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var builder =
        Team.newBuilder()
            .withName("team")
            .withModel(new MockModel("ok"))
            .withWorker("worker", "Does work", worker)
            .withMaxIterations(0);

    var ex = assertThrows(IllegalStateException.class, builder::build);
    assertTrue(ex.getMessage().contains("maxIterations"));
  }

  @Test
  void builderWithAllOptions() {
    var workerModel = new MockModel("Worker result.");
    var worker =
        new Agent(
            AgentConfig.newBuilder()
                .withName("Worker")
                .withModel(workerModel)
                .withIncludeMemoryTools(false)
                .build());

    var publishTool =
        Tool.newBuilder()
            .withName("publish")
            .withDescription("Publish")
            .withExecutor(args -> ToolResult.success("Published"))
            .build();

    var traces = new ArrayList<Trace>();
    var memory = InMemoryMemory.withDefaults();

    var leaderCallCount = new AtomicInteger(0);
    var leaderModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (leaderCallCount.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("call_1")
                              .withName("worker")
                              .withArguments(Map.of("task", "Do work"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("Done with all options")
                .withFinishReason(FinishReason.STOP)
                .build();
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
            .withName("full-team")
            .withModel(leaderModel)
            .withWorker("worker", "Does work", worker)
            .withTools(List.of(publishTool))
            .withMemory(memory)
            .withTraceListeners(List.of(traces::add))
            .withFaultTolerance(FaultTolerance.PASSTHROUGH)
            .withTraceDetail(TraceDetail.VERBOSE)
            .withIncludeMemoryTools(false)
            .build();

    var result = team.run("Test all options");

    assertTrue(result.isSuccess());
    assertEquals("Done with all options", ((Result.Success<Response>) result).value().content());
    assertEquals(1, traces.size());
    assertTrue(traces.getFirst().success());

    var toolSpan =
        traces.getFirst().spans().stream()
            .filter(s -> s.kind() == SpanKind.TOOL_EXECUTION)
            .filter(s -> "worker".equals(s.attributes().get("toolName")))
            .findFirst();
    assertTrue(toolSpan.isPresent());
    assertTrue(toolSpan.get().attributes().containsKey("arguments"));
    assertTrue(toolSpan.get().attributes().containsKey("result"));
  }
}
