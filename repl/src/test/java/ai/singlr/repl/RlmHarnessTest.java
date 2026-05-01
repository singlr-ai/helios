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
  void requiredSignatureMissingInjectsCorrectiveAndModelRetries() {
    // End-to-end behavior test for the 1.1.4 required-signature feature.
    // - Iter 1: the model submits a clean output BUT skips a required predict() signature.
    // - The harness's iteration hook fires, sees submit-set + signature-missing, injects a
    //   corrective USER message naming the missing signature.
    // - Iter 2: the model receives the injection, calls predict() with the EXACT registered
    //   signature instructions text. Detection fires; the signature is recorded.
    // - Iter 3: the model stops. Hook sees submit-set + no missing sigs, allows. Run ends
    //   SUBMITTED.
    // Without this test we'd only know the detection records correctly — not that the corrective
    // message reaches the model and the run actually recovers.
    var devilsAdvocateInstructions = "Take the opposing view on the consensus.";

    var sandboxCalls = new AtomicInteger();
    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              // RlmHarness runs InputBindings.snippet (calls HostBridge.getInput) BEFORE the
              // agent loop. Don't count that against the model-driven counter.
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              var n = sandboxCalls.incrementAndGet();
              if (n == 1) {
                // First execute_code: model submits without calling devil's advocate.
                try {
                  registry
                      .get("submit")
                      .handler()
                      .handle(
                          Map.of("output", Map.of("answer", "consensus says X", "wordCount", 3)));
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                return ExecutionResult.success("[submitted]");
              }
              // Second execute_code: model now calls predict() with the required signature.
              try {
                registry
                    .get("predict")
                    .handler()
                    .handle(Map.of("instructions", devilsAdvocateInstructions, "input", "Q"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[predict invoked]");
            };

    // Mock model: emits a tool call on iter 1 + iter 2, stops on iter 3.
    // The injected corrective USER message between iter 1 and iter 2 should arrive in the model's
    // input — we don't introspect it here (that's RlmSystemPromptTest territory); we only check
    // the loop terminates SUBMITTED, which proves the corrective fired and the model recovered.
    var rootModel =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var t = turn.getAndIncrement();
            if (t < 2) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c" + t)
                              .withName("execute_code")
                              .withArguments(Map.of("code", "scripted-on-iter-" + t))
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
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    // Mock predict: always succeeds, but importantly its handler must run so the
    // calledSignatures tracker fires.
    var subModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("opposing view ack")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "sub";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .subModel(subModel)
            .sandboxFactory(scriptedSandboxFactory(sandboxScript))
            .requiredPredictSignature(
                new RequiredPredictSignature("devils_advocate", devilsAdvocateInstructions))
            .maxIterations(10)
            .build();

    var result = harness.run(new Input("topic"));

    assertEquals(
        RlmResult.Status.SUBMITTED,
        result.status(),
        "loop must end SUBMITTED after the corrective injection prompts the model to call DA. "
            + "Got error: "
            + result.error());
    assertEquals("consensus says X", result.output().answer());
    // Critical: prove both execute_code turns ran. If the hook didn't inject, sandboxCalls would
    // be 1 (just the initial submit-without-DA).
    assertEquals(2, sandboxCalls.get(), "iter 1 (initial submit) + iter 2 (DA after correction)");
  }

  @Test
  void typedSubmitValidationFailureRetriesAndSucceeds() {
    // End-to-end behavior test for the typed-submit inline-retry path.
    // - Iter 1: model calls submit() with a Map missing required field 'wordCount'. The submit
    //   handler throws IllegalArgumentException. The script catches it and surfaces as an error
    //   string in the tool result — same as the JSON-RPC bridge would.
    // - Iter 2: model receives the validation error in the next tool result. It calls submit()
    //   again with a complete map. CAS commits.
    // - Iter 3: model stops. Hook allows. Run ends SUBMITTED with the corrected output.
    // Without this test we'd only know the host-side handler retries — not that the agent loop
    // gives the model a second turn after a validation failure.
    var sandboxCalls = new AtomicInteger();
    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              // RlmHarness runs InputBindings.snippet (calls HostBridge.getInput) BEFORE the
              // agent loop. Don't count that against the model-driven counter.
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              var n = sandboxCalls.incrementAndGet();
              if (n == 1) {
                // Submit without wordCount — schema requires both fields.
                try {
                  registry
                      .get("submit")
                      .handler()
                      .handle(Map.of("output", Map.of("answer", "first try")));
                } catch (IllegalArgumentException expected) {
                  return ExecutionResult.success("Error: " + expected.getMessage());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                return ExecutionResult.success("[unexpected: validation should have failed]");
              }
              // Iter 2: full valid submit.
              try {
                registry
                    .get("submit")
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "second try", "wordCount", 2)));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[submitted-cleanly]");
            };

    var rootModel =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var t = turn.getAndIncrement();
            if (t < 2) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c" + t)
                              .withName("execute_code")
                              .withArguments(Map.of("code", "scripted"))
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
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .sandboxFactory(scriptedSandboxFactory(sandboxScript))
            .maxIterations(5)
            .build();

    var result = harness.run(new Input("hi"));

    assertEquals(RlmResult.Status.SUBMITTED, result.status(), result.error());
    // Critical: the SECOND submit's value should be the one we got, not the first invalid one.
    // CAS in SubmitFunction means the holder was never set on iter 1 (validation threw before
    // the holder.compareAndSet), so iter 2's value lands cleanly.
    assertEquals("second try", result.output().answer());
    assertEquals(2, result.output().wordCount());
    assertEquals(2, sandboxCalls.get(), "iter 1 (failed submit) + iter 2 (valid resubmit)");
  }

  @Test
  void budgetExhaustionAllowsGracefulSubmit() {
    // End-to-end behavior test that the model can recover after the predict() budget trips.
    // - maxLlmCalls = 2: third predict() call throws SandboxBudgetExceededException.
    // - Iter 1: model calls predict (counter 1).
    // - Iter 2: model calls predict (counter 2).
    // - Iter 3: model calls predict (would be counter 3, throws). Script catches, returns
    //   "Error: predict() budget..." in tool result.
    // - Iter 4: model receives the error, calls submit() instead. CAS commits.
    // - Iter 5: model stops. Hook allows. Run ends SUBMITTED with predictCallCount=3 (the third
    //   call was attempted but rejected; the counter still incremented per-attempt).
    var sandboxCalls = new AtomicInteger();
    var lastError = new AtomicReference<String>();
    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              // RlmHarness runs InputBindings.snippet (calls HostBridge.getInput) BEFORE the
              // agent loop. Don't count that against the model-driven counter.
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              var n = sandboxCalls.incrementAndGet();
              if (n <= 3) {
                try {
                  registry
                      .get("predict")
                      .handler()
                      .handle(Map.of("instructions", "do thing", "input", "in"));
                } catch (SandboxBudgetExceededException budget) {
                  lastError.set(budget.getMessage());
                  return ExecutionResult.success("Error: " + budget.getMessage());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                return ExecutionResult.success("[predict ok " + n + "]");
              }
              // Iter 4: submit gracefully.
              try {
                registry
                    .get("submit")
                    .handler()
                    .handle(
                        Map.of("output", Map.of("answer", "best effort answer", "wordCount", 3)));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[submitted-after-budget-trip]");
            };

    var rootModel =
        new Model() {
          final AtomicInteger turn = new AtomicInteger();

          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            var t = turn.getAndIncrement();
            if (t < 4) {
              return Response.newBuilder()
                  .withToolCalls(
                      List.of(
                          ToolCall.newBuilder()
                              .withId("c" + t)
                              .withName("execute_code")
                              .withArguments(Map.of("code", "scripted"))
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
          public String id() {
            return "mock";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var subModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("ack")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          public String id() {
            return "sub";
          }

          @Override
          public String provider() {
            return "test";
          }
        };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .subModel(subModel)
            .sandboxFactory(scriptedSandboxFactory(sandboxScript))
            .maxLlmCalls(2)
            .maxIterations(10)
            .build();

    var result = harness.run(new Input("topic"));

    assertEquals(RlmResult.Status.SUBMITTED, result.status(), result.error());
    assertEquals("best effort answer", result.output().answer());
    // Critical: the budget exception fired and the error text reached us via the tool result —
    // proving the model would have seen the same string.
    assertNotNull(lastError.get(), "predict budget should have tripped before iter 4");
    assertTrue(
        lastError.get().contains("budget"),
        "exception message must mention budget so the model knows what tripped");
    assertEquals(4, sandboxCalls.get(), "iters 1-3 predict (3rd trips), iter 4 submit");
    assertEquals(3, result.predictCallCount(), "counter increments per-attempt including the trip");
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
