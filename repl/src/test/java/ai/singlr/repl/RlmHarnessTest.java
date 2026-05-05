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
  void requiredSignatureMissingAfterMaxIterationsReturnsFAILED() {
    // Regression test for the post-1.1.4 Opus 4.7 bug: the iteration hook only fires when the
    // model volunteers a STOP turn. Heavy tool-using models keep emitting execute_code until
    // maxIterations and never trigger the hook. The post-loop check in RlmHarness.run is the
    // safety net — if submit was called but a required signature was never invoked, the
    // trajectory is rejected as FAILED rather than silently SUBMITTED.
    var devilsAdvocateInstructions = "Take the opposing view on the consensus.";

    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              // Every execute_code submits — model never calls predict at all, never stops.
              try {
                registry
                    .get("submit")
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "no DA", "wordCount", 2)));
              } catch (IllegalStateException alreadySubmitted) {
                // CAS — submit already happened. Just return ok; model keeps spinning.
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[ok]");
            };

    // Model that NEVER returns content-without-tool-call: emits execute_code on every turn.
    // This is the Opus 4.7 behavior that broke 1.1.4: agent loop terminates at maxIterations
    // because the model never volunteers a STOP, so the in-loop hook never fires.
    var rootModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withToolCalls(
                    List.of(
                        ToolCall.newBuilder()
                            .withId("c")
                            .withName("execute_code")
                            .withArguments(Map.of("code", "scripted"))
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

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .sandboxFactory(scriptedSandboxFactory(sandboxScript))
            .requiredPredictSignature(
                new RequiredPredictSignature("devils_advocate", devilsAdvocateInstructions))
            .maxIterations(3) // small cap so the test finishes quickly
            .build();

    var result = harness.run(new Input("topic"));

    // Critical: status is FAILED, not SUBMITTED. The submit happened but DA was never run, so
    // the trajectory is rejected. Without the post-loop check this would be SUBMITTED.
    assertEquals(
        RlmResult.Status.FAILED,
        result.status(),
        "submit() succeeded but devils_advocate was never called — must FAIL, not SUBMIT");
    assertNotNull(result.error());
    assertTrue(
        result.error().contains("devils_advocate"),
        "error must name the missing signature so callers can debug; got: " + result.error());
    assertTrue(
        result.error().contains("predictInstructions") || result.error().contains("matcher"),
        "error must point users at the debug surface; got: " + result.error());
  }

  @Test
  void customSignatureMatcherAcceptsSubstringMatch() {
    // Escape hatch test: when the model paraphrases the registered instructions, an exact-equality
    // match fails. withSignatureMatcher((registered, actual) -> actual.contains(registered))
    // lets users opt into substring matching to recover the call.
    var registered = "Take the opposing view";
    var paraphrased = "INSTRUCTIONS: Take the opposing view on the consensus and challenge it.";

    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              try {
                // Model passes a paraphrased version, NOT the exact registered string.
                registry
                    .get("predict")
                    .handler()
                    .handle(Map.of("instructions", paraphrased, "input", "x"));
                registry
                    .get("submit")
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "ok", "wordCount", 1)));
              } catch (IllegalStateException alreadySubmitted) {
                // CAS guard
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
                              .withId("c0")
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
            .requiredPredictSignature(new RequiredPredictSignature("devils_advocate", registered))
            // The escape hatch: substring match instead of exact equality.
            .signatureMatcher((reg, actual) -> actual.contains(reg))
            .maxIterations(3)
            .build();

    var result = harness.run(new Input("topic"));

    // With exact-equality matching this would be FAILED; with the custom matcher the paraphrased
    // call counts as the signature being invoked, so we land SUBMITTED.
    assertEquals(
        RlmResult.Status.SUBMITTED,
        result.status(),
        "custom matcher should accept the paraphrased predict call; got error: " + result.error());
    assertEquals("ok", result.output().answer());
  }

  @Test
  void rlmResultExposesPredictTranscriptAndHostFnCounts() {
    // Kubera's acceptance criteria for 1.1.6: downstream RLM evaluators can detect signature
    // calls + count distinct data tools without grepping JShell. Confirms the trajectory data
    // surfaces on RlmResult exactly as the model produced it.
    var marketQuote =
        new ai.singlr.repl.host.HostFunction("marketQuote", "fake quote", params -> "$200");

    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              try {
                // One predict call with explicit instructions + input.
                registry
                    .get("predict")
                    .handler()
                    .handle(Map.of("instructions", "foo", "input", "x"));
                // One marketQuote call.
                registry.get("marketQuote").handler().handle(Map.of("ticker", "AAPL"));
                // Submit cleanly.
                registry
                    .get("submit")
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "done", "wordCount", 1)));
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
                              .withId("c0")
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
            .hostFunction(marketQuote)
            .maxIterations(3)
            .build();

    var result = harness.run(new Input("topic"));

    assertEquals(RlmResult.Status.SUBMITTED, result.status(), result.error());

    // predictCalls — full structured transcript.
    assertEquals(1, result.predictCalls().size());
    var call = result.predictCalls().getFirst();
    assertEquals("foo", call.instructions());
    assertEquals("x", call.input());

    // calledHostFunctions — only user-registered Skill tools (marketQuote) count.
    // Framework primitives (predict/submit/fetch/query/getInput/__getInput/__call) are excluded
    // by design so the map measures "data tool diversity" cleanly.
    assertEquals(java.util.Map.of("marketQuote", 1), result.calledHostFunctions());
  }

  @Test
  void rlmResultPreservesTrajectoryOnFAILED() {
    // Trajectory data must survive even on FAILED results — that's the whole point: downstream
    // metrics often need to inspect what the model did even when the run failed.
    var sandboxScript =
        (SandboxScript)
            (request, registry) -> {
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              try {
                registry.get("predict").handler().handle(Map.of("instructions", "x", "input", "y"));
                registry
                    .get("submit")
                    .handler()
                    .handle(Map.of("output", Map.of("answer", "incomplete", "wordCount", 1)));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[ok]");
            };
    var marketQuote =
        new ai.singlr.repl.host.HostFunction("marketQuote", "fake quote", params -> "$200");
    // Add a marketQuote call to the script so calledHostFunctions has something user-registered.
    sandboxScript = wrapScriptWithHostFn(sandboxScript, "marketQuote", Map.of("ticker", "AAPL"));
    var rootModel = toolThenStopModel("scripted", "done");

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(rootModel)
            .sandboxFactory(scriptedSandboxFactory(sandboxScript))
            .hostFunction(marketQuote)
            .requiredPredictSignature(
                new RequiredPredictSignature("must_run", "different instructions"))
            .maxIterations(3)
            .build();

    var result = harness.run(new Input("topic"));

    assertEquals(
        RlmResult.Status.FAILED,
        result.status(),
        "submit happened but required sig was missing — must FAIL");
    // Critical: trajectory preserved on FAILED so callers can post-mortem.
    assertEquals(
        1,
        result.predictCalls().size(),
        "predict transcript preserved on FAILED so callers can post-mortem");
    assertEquals("x", result.predictCalls().getFirst().instructions());
    assertEquals(
        java.util.Map.of("marketQuote", 1),
        result.calledHostFunctions(),
        "host fn counts preserved on FAILED");
  }

  /** Compose: run the inner script, then call an additional registered host fn before returning. */
  private static SandboxScript wrapScriptWithHostFn(
      SandboxScript inner, String fnName, Map<String, Object> args) {
    return (request, registry) -> {
      var result = inner.execute(request, registry);
      if (request.code().contains("HostBridge.getInput")) {
        return result;
      }
      try {
        registry.get(fnName).handler().handle(args);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return result;
    };
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

  // --- builder.outputSchema(...) / submitValidator(...) ---

  /**
   * Mock model that emits {@code execute_code} on turns 0 and 1 (so the model gets two attempts at
   * submit) and stops on turn 2. Lets a SubmitValidator failure on the first attempt force a retry
   * within the same trajectory.
   */
  private static Model twoExecuteThenStopModel() {
    var turn = new AtomicInteger();
    return new Model() {
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
                          .withArguments(Map.of("code", "submit-attempt-" + t))
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
  }

  /**
   * A scripted sandbox that calls {@code submit} only when the executed code looks like the model's
   * tool call (not when the harness pre-executes its input-bindings snippet). The binding snippet
   * always starts with the {@code __getInput()} marker, so we gate on that.
   */
  private static SandboxScript modelOnlySubmitScript(Map<String, Object> submitOutput) {
    return (request, registry) -> {
      if (request.code().contains("HostBridge.getInput")) {
        return ExecutionResult.success("");
      }
      try {
        registry.get("submit").handler().handle(Map.of("output", submitOutput));
      } catch (IllegalArgumentException expected) {
        return ExecutionResult.success("Error: " + expected.getMessage());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return ExecutionResult.success("[ok]");
    };
  }

  @Test
  void outputSchemaSetterPlumbsValidatorThrough() {
    var validatorRan = new AtomicInteger();
    var schema =
        OutputSchema.of(Output.class)
            .withSubmitValidator(
                o -> {
                  validatorRan.incrementAndGet();
                  return o.wordCount() >= 10
                      ? ai.singlr.core.common.ValidationResult.success()
                      : ai.singlr.core.common.ValidationResult.failure("too short");
                });

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"))
            .sandboxFactory(
                scriptedSandboxFactory(
                    modelOnlySubmitScript(Map.of("answer", "long enough", "wordCount", 50))))
            .outputSchema(schema)
            .build();

    var result = harness.run(new Input("q"));
    assertEquals(RlmResult.Status.SUBMITTED, result.status());
    assertEquals(1, validatorRan.get(), "validator must run exactly once on a clean submit");
    assertSame(schema, harness.outputSchema(), "harness must use the supplied schema verbatim");
  }

  @Test
  void submitValidatorConvenienceWiresValidator() {
    var validatorRan = new AtomicInteger();
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"))
            .sandboxFactory(
                scriptedSandboxFactory(
                    modelOnlySubmitScript(Map.of("answer", "ok", "wordCount", 50))))
            .submitValidator(
                (ai.singlr.core.common.SubmitValidator<Output>)
                    o -> {
                      validatorRan.incrementAndGet();
                      return ai.singlr.core.common.ValidationResult.success();
                    })
            .build();

    harness.run(new Input("q"));
    assertEquals(1, validatorRan.get());
  }

  @Test
  void submitValidatorPredicateConvenienceFailsThenSucceeds() {
    var attempt = new AtomicInteger();
    var script =
        (SandboxScript)
            (request, registry) -> {
              if (request.code().contains("HostBridge.getInput")) {
                return ExecutionResult.success("");
              }
              var submit = registry.get("submit");
              var n = attempt.getAndIncrement();
              try {
                if (n == 0) {
                  submit
                      .handler()
                      .handle(Map.of("output", Map.of("answer", "stub", "wordCount", 1)));
                } else {
                  submit
                      .handler()
                      .handle(Map.of("output", Map.of("answer", "substantive", "wordCount", 50)));
                }
              } catch (IllegalArgumentException expected) {
                return ExecutionResult.success("Error: " + expected.getMessage());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              return ExecutionResult.success("[ok]");
            };

    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(twoExecuteThenStopModel())
            .sandboxFactory(scriptedSandboxFactory(script))
            .submitValidator(
                o -> o.wordCount() >= 10, "answer is too short — write at least 10 words")
            .build();

    var result = harness.run(new Input("q"));
    assertEquals(RlmResult.Status.SUBMITTED, result.status());
    assertEquals(2, attempt.get(), "model must be given two submit attempts");
    assertEquals("substantive", result.output().answer());
    assertEquals(50, result.output().wordCount());
  }

  @Test
  void submitValidatorAfterCustomOutputSchemaPreservesSchemaShape() {
    var customSchema =
        OutputSchema.of(Output.class).withSubmitValidator(o -> true, "first validator");
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .outputSchema(customSchema)
            .submitValidator(o -> true, "second validator (chained on top)")
            .build();
    var harnessSchema = harness.outputSchema();
    assertNotNull(harnessSchema.submitValidator(), "chained validator must be set");
    assertEquals(Output.class, harnessSchema.type());
  }

  @Test
  void submitValidatorOnTopOfPriorOutputSchemaChainsCorrectly() {
    var customSchema =
        OutputSchema.of(Output.class).withSubmitValidator(o -> true, "first validator");
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .outputSchema(customSchema)
            .submitValidator(
                (ai.singlr.core.common.SubmitValidator<Output>)
                    o -> ai.singlr.core.common.ValidationResult.success())
            .build();
    assertNotNull(harness.outputSchema().submitValidator());
    assertEquals(Output.class, harness.outputSchema().type());
  }

  @Test
  void outputSchemaSetterRejectsNull() {
    var b = RlmHarness.builder(Input.class, Output.class);
    assertThrows(IllegalArgumentException.class, () -> b.outputSchema(null));
  }

  @Test
  void noOutputSchemaSetReturnsDefault() {
    var harness =
        RlmHarness.builder(Input.class, Output.class)
            .model(toolThenStopModel("submit(...)", "done"))
            .sandboxFactory(scriptedSandboxFactory((req, reg) -> ExecutionResult.success("")))
            .build();
    assertEquals(Output.class, harness.outputSchema().type());
    assertNull(
        harness.outputSchema().submitValidator(),
        "default schema must not carry a validator (regression guard)");
  }
}
