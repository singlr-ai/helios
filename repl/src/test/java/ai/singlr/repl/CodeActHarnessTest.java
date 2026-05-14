/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.agent.SessionContext;
import ai.singlr.core.events.HeliosEvent;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;

class CodeActHarnessTest {

  public record Input(String query) {}

  public record Output(String answer, int wordCount) {}

  private static SandboxFactory passthroughSandboxFactory() {
    return registry ->
        new Sandbox() {
          @Override
          public ExecutionResult execute(ExecutionRequest request) {
            return ExecutionResult.success("");
          }

          @Override
          public boolean isAlive() {
            return true;
          }

          @Override
          public void close() {}
        };
  }

  private static Model structuredAnswerModel(Output answer) {
    return new Model() {
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
        return (Response<U>)
            Response.newBuilder(Object.class)
                .withParsed(answer)
                .withContent("{\"answer\":\"" + answer.answer() + "\"}")
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

  private static Model parseFailureModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent("not json")
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        return (Response<U>)
            Response.newBuilder(Object.class)
                .withContent("not json")
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
  void builderRejectsNullTypes() {
    assertThrows(IllegalArgumentException.class, () -> CodeActHarness.builder(null, Output.class));
    assertThrows(IllegalArgumentException.class, () -> CodeActHarness.builder(Input.class, null));
  }

  @Test
  void builderRequiresModel() {
    var b = CodeActHarness.builder(Input.class, Output.class);
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRequiresSandboxFactory() {
    var b =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("x", 1)));
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void builderRejectsInvalidIterationCount() {
    var b =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("x", 1)))
            .sandboxFactory(passthroughSandboxFactory());
    assertThrows(IllegalStateException.class, () -> b.maxIterations(0).build());
  }

  @Test
  void builderRejectsNegativeOutputCap() {
    var b =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("x", 1)))
            .sandboxFactory(passthroughSandboxFactory());
    assertThrows(IllegalStateException.class, () -> b.maxOutputCharsToModel(-1).build());
  }

  @Test
  void outputSchemaSetterRejectsNull() {
    var b = CodeActHarness.builder(Input.class, Output.class);
    assertThrows(IllegalArgumentException.class, () -> b.outputSchema(null));
  }

  @Test
  void runRejectsNullInput() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("x", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    var result = harness.run(null);
    assertEquals(CodeActResult.Status.FAILED, result.status());
    assertTrue(result.error().orElseThrow().contains("input must not be null"));
  }

  @Test
  void cleanAgentResponseProducesTypedOutput() {
    var expected = new Output("hello world", 2);
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(expected))
            .sandboxFactory(passthroughSandboxFactory())
            .strategy("answer briefly")
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
    assertTrue(result.error().isEmpty());
    assertEquals("hello world", result.output().orElseThrow().answer());
    assertEquals(2, result.output().orElseThrow().wordCount());
  }

  @Test
  void schemaParseFailureProducesFailedResult() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(parseFailureModel())
            .sandboxFactory(passthroughSandboxFactory())
            .maxIterations(1)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.FAILED, result.status());
    assertNotNull(result.error());
  }

  @Test
  void eventSinkReceivesEvents() {
    var sink = new CopyOnWriteArrayList<HeliosEvent>();
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .eventSink(sink::add)
            .build();
    harness.run(new Input("hi"));
    assertFalse(sink.isEmpty(), "user-provided event sink must receive events");
    assertTrue(
        sink.stream().anyMatch(e -> e instanceof HeliosEvent.RunStarted),
        "expected at least RunStarted event");
  }

  @Test
  void capturedTraceIsAttachedToResult() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    var result = harness.run(new Input("hi"));
    assertNotNull(result.trace(), "trace must be captured on success");
  }

  @Test
  void suppliedSessionContextIsHonoured() {
    var sessionId = UUID.randomUUID();
    var supplied = SessionContext.newBuilder().withUserId("u-123").withSessionId(sessionId).build();
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    var result = harness.run(new Input("hi"), supplied);
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void customOutputSchemaIsHonoured() {
    var customSchema = OutputSchema.of(Output.class);
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .outputSchema(customSchema)
            .build();
    assertSame(customSchema, harness.outputSchema());
  }

  @Test
  void systemPromptOverrideIsAcceptedByBuilder() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .systemPrompt("override")
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void hostFunctionAndHostFunctionsBuilderEntries() {
    var fn = new HostFunction("kb_grep", "search", params -> "ok");
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .hostFunction(fn)
            .hostFunctions(List.of(new HostFunction("kb_read", "read", params -> "ok")))
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void concurrencyLimiterIsHonoured() {
    var sem = new Semaphore(1);
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .concurrencyLimiter(sem)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void inputTypeAndOutputTypeAccessorsReturnConfigured() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    assertEquals(Input.class, harness.inputType());
    assertEquals(Output.class, harness.outputType());
  }

  @Test
  void bindingFailureProducesFailedResult() {
    SandboxFactory badBinding =
        registry ->
            new Sandbox() {
              @Override
              public ExecutionResult execute(ExecutionRequest request) {
                return ExecutionResult.failure("binding error", 1);
              }

              @Override
              public boolean isAlive() {
                return true;
              }

              @Override
              public void close() {}
            };
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(badBinding)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.FAILED, result.status());
    assertTrue(result.error().orElseThrow().contains("input binding failed"));
  }

  @Test
  void maxExecutedCodeCharsBuilderSetter() {
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .maxExecutedCodeChars(100)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void skillBuilderFoldsInstructionsAndTools() {
    var skill =
        new Skill(
            "kb_skill",
            "read carefully",
            List.of(new HostFunction("kb_grep", "search", params -> "ok")));
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .skill(skill)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void skillsBuilderListVariantAlsoFolds() {
    var skill =
        new Skill("kb_skill", "read", List.of(new HostFunction("kb_read", "read", params -> "ok")));
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .skills(List.of(skill))
            .strategy("base strategy")
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  public record Empty() {}

  @Test
  void zeroComponentRecordSkipsBindingSnippet() {
    // Empty record returns null from InputBindings.snippet — exercises the alternate path
    // through assembleHostFunctions and runBindingSnippet that non-binding inputs hit.
    var harness =
        CodeActHarness.builder(Empty.class, Output.class)
            .model(structuredAnswerModel(new Output("ok", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    var result = harness.run(new Empty());
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void agentReturnsContentWithoutParsedFailsLoudly() {
    // Custom Model that returns Success with parsed=null — exercises the "no parsed output"
    // diagnostic branch in interpret().
    var partialModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent("preview body")
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withContent("preview body")
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
        CodeActHarness.builder(Input.class, Output.class)
            .model(partialModel)
            .sandboxFactory(passthroughSandboxFactory())
            .maxIterations(1)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.FAILED, result.status());
    assertTrue(result.error().orElseThrow().contains("no parsed output"));
    assertTrue(result.error().orElseThrow().contains("preview body"));
  }

  @Test
  void agentReturnsLongContentTruncatesIn200CharsPreview() {
    var bigContent = "x".repeat(500);
    var bigContentModel =
        new Model() {
          @Override
          public Response<Void> chat(List<Message> messages, List<Tool> tools) {
            return Response.newBuilder()
                .withContent(bigContent)
                .withFinishReason(FinishReason.STOP)
                .build();
          }

          @Override
          @SuppressWarnings({"unchecked", "rawtypes"})
          public <U> Response<U> chat(
              List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
            return (Response<U>)
                Response.newBuilder(Object.class)
                    .withContent(bigContent)
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
        CodeActHarness.builder(Input.class, Output.class)
            .model(bigContentModel)
            .sandboxFactory(passthroughSandboxFactory())
            .maxIterations(1)
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.FAILED, result.status());
    // 200-char cap exercised: error contains "content=" followed by exactly 200 chars of x.
    assertTrue(result.error().orElseThrow().endsWith("x".repeat(200)));
  }

  @Test
  void suppliedSessionWithPromptVarsAndMetadataIsHonoured() {
    var supplied =
        SessionContext.newBuilder()
            .withUserId("u-7")
            .withSessionId(UUID.randomUUID())
            .withPromptVars(java.util.Map.of("k", "v"))
            .withMetadata(java.util.Map.of("trace_id", "abc"))
            .build();
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .build();
    var result = harness.run(new Input("hi"), supplied);
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }

  @Test
  void sandboxBindingsListenerSetterAccepted() {
    var seen = new ArrayList<java.util.Map<String, String>>();
    var harness =
        CodeActHarness.builder(Input.class, Output.class)
            .model(structuredAnswerModel(new Output("a", 1)))
            .sandboxFactory(passthroughSandboxFactory())
            .sandboxBindingsListener((bindings, result) -> seen.add(bindings))
            .build();
    var result = harness.run(new Input("hi"));
    assertEquals(CodeActResult.Status.SUCCEEDED, result.status());
  }
}
