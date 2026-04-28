/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RlmHarnessTest {

  public record Input(String query) {}

  public record Output(String answer, int wordCount) {}

  /**
   * Stub sandbox where every {@code execute_code} call invokes a configurable scripted handler
   * passing through the registry, so tests can simulate the model writing JShell that calls {@code
   * submit(...)}.
   */
  private static SandboxFactory scriptedSandboxFactory(SandboxScript script) {
    return registry ->
        new Sandbox() {
          @Override
          public ExecutionResult execute(ExecutionRequest request) {
            return script.execute(request, registry);
          }

          @Override
          public boolean isAlive() {
            return true;
          }

          @Override
          public void close() {}
        };
  }

  @FunctionalInterface
  interface SandboxScript {
    ExecutionResult execute(
        ExecutionRequest request, ai.singlr.repl.host.HostFunctionRegistry registry);
  }

  /**
   * Mock model that emits a single tool call to {@code execute_code} on the first turn, then
   * returns plain content on the second turn so the agent loop terminates.
   */
  private static Model toolThenStopModel(String code, String finalText) {
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
                          .withName("execute_code")
                          .withArguments(Map.of("code", code))
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
  void builderRequiresModel() {
    var b = RlmHarness.builder(Input.class, Output.class);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRequiresSandboxFactory() {
    var b =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"));
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsNullTypes() {
    assertThrows(IllegalArgumentException.class, () -> RlmHarness.builder(null, Output.class));
    assertThrows(IllegalArgumentException.class, () -> RlmHarness.builder(Input.class, null));
  }

  @Test
  void builderRejectsInvalidIterationCounts() {
    var common =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("", ""))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")));
    assertThrows(IllegalStateException.class, () -> common.maxIterations(0).build());
  }

  @Test
  void runRejectsNullInput() {
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(Map.of())", "done"))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .build();
    var result = harness.run(null);
    assertEquals(RlmResult.Status.FAILED, result.status());
    assertTrue(result.error().contains("input must not be null"));
  }

  @Test
  void cleanSubmitProducesTypedOutput() {
    var script =
        (SandboxScript)
            (request, registry) -> {
              var submit = registry.get("submit");
              try {
                submit
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "hello world", "wordCount", 2)));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success(
                  "[ok]", Map.of("answer", "hello world", "wordCount", 2));
            };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(Map.of(\"answer\", \"hello world\", ...))", "done"))
            .sandboxFactory(scriptedSandboxFactory(script))
            .strategy("answer the query, briefly")
            .maxLlmCalls(3)
            .build();

    var result = harness.run(new Input("hi"));

    assertEquals(RlmResult.Status.SUBMITTED, result.status());
    assertNull(result.error());
    assertNotNull(result.output());
    assertEquals("hello world", result.output().answer());
    assertEquals(2, result.output().wordCount());
  }

  @Test
  void usesExtractFallbackWhenAgentEndsWithoutSubmit() {
    var fallbackResponse = new AtomicReference<Output>();
    fallbackResponse.set(new Output("from extract-fallback", 3));

    var fallbackModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            // The extract-fallback Agent uses this typed chat overload.
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withParsed(fallbackResponse.get())
                    .withContent("structured")
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
        };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(fallbackModel)
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .build();

    var result = harness.run(new Input("hi"));

    assertEquals(RlmResult.Status.EXTRACTED, result.status());
    assertEquals("from extract-fallback", result.output().answer());
    assertEquals(3, result.output().wordCount());
  }

  @Test
  void submitWithSchemaMismatchSurfacesAsValidationFailure() {
    var script =
        (SandboxScript)
            (request, registry) -> {
              var submit = registry.get("submit");
              try {
                submit.handler().handle(Map.of("output", Map.of("answer", "missing wordCount")));
              } catch (IllegalArgumentException expected) {
                return ExecutionResult.success("Error: " + expected.getMessage());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[ok]");
            };

    var rootModel =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            if (turn.getAndIncrement() == 0) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c1")
                              .withName("execute_code")
                              .withArguments(Map.of("code", "submit(invalid)"))
                              .build()))
                  .withFinishReason(FinishReason.TOOL_CALLS)
                  .build();
            }
            return Response.newBuilder()
                .withContent("done")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withParsed(new Output("recovered via fallback", 5))
                    .withContent("structured")
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
        };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .sandboxFactory(scriptedSandboxFactory(script))
            .build();

    var result = harness.run(new Input("hi"));

    // Submit failed validation, so the holder is null and the harness falls back to extract.
    assertEquals(RlmResult.Status.EXTRACTED, result.status());
    assertEquals("recovered via fallback", result.output().answer());
  }

  @Test
  void exposesInputAndOutputTypes() {
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("", ""))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .build();
    assertSame(Input.class, harness.inputType());
    assertSame(Output.class, harness.outputType());
    assertSame(Output.class, harness.outputSchema().type());
  }

  @Test
  void supportsCustomSystemPromptOverride() {
    var captured = new AtomicReference<String>();
    var inspectingModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            for (var m : messages) {
              if (m.role() == ai.singlr.core.model.Role.SYSTEM) {
                captured.set(m.content());
              }
            }
            return Response.newBuilder()
                .withContent("ok")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withParsed(new Output("x", 1))
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
        };
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(inspectingModel)
            .systemPrompt("CUSTOM SYSTEM PROMPT MARKER")
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .build();

    harness.run(new Input("hi"));

    assertEquals("CUSTOM SYSTEM PROMPT MARKER", captured.get());
  }
}
