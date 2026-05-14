/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LlmReflectiveMutatorTest {

  private static Model firstAttemptModel(String responseText, AtomicReference<String> seenPrompt) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        if (seenPrompt != null) {
          seenPrompt.set(messages.get(messages.size() - 1).content());
        }
        return Response.newBuilder()
            .withContent(responseText)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        throw new IllegalStateException(
            "structured chat() should not be invoked when first attempt is acceptable");
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

  private static Model firstBadThenStructuredModel(
      String firstBadResponse, String structuredPrompt) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder()
            .withContent(firstBadResponse)
            .withFinishReason(FinishReason.STOP)
            .build();
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        return (Response<U>)
            Response.newBuilder(Object.class)
                .withParsed(new LlmReflectiveMutator.RevisedPrompt(structuredPrompt))
                .withContent("{\"prompt\":\"" + structuredPrompt + "\"}")
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

  private static Model bothFailModel(String first, String structuredEmpty) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(first).withFinishReason(FinishReason.STOP).build();
      }

      @Override
      @SuppressWarnings({"unchecked", "rawtypes"})
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        return (Response<U>)
            Response.newBuilder(Object.class)
                .withParsed(new LlmReflectiveMutator.RevisedPrompt(structuredEmpty))
                .withContent("{}")
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
  }

  private static Model retryThrowsModel(String first) {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent(first).withFinishReason(FinishReason.STOP).build();
      }

      @Override
      public <U> Response<U> chat(
          List<Message> messages, List<Tool> tools, OutputSchema<U> schema) {
        throw new RuntimeException("schema chat boom");
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
  }

  @Test
  void builderRejectsNullModel() {
    assertThrows(IllegalArgumentException.class, () -> LlmReflectiveMutator.builder(null));
  }

  @Test
  void firstAttemptAccepted() {
    var seenPrompt = new AtomicReference<String>();
    var model = firstAttemptModel("This is the new prompt, longer than 25% of parent.", seenPrompt);
    var mutator = LlmReflectiveMutator.builder(model).build();
    var revised = mutator.propose("Original prompt text here.", List.of());
    assertEquals("This is the new prompt, longer than 25% of parent.", revised);
    assertTrue(
        seenPrompt.get().contains("## Current prompt"),
        "reflection prompt must contain the standard sections");
  }

  @Test
  void firstAttemptStrippedOfCodeFencesAndPreamble() {
    var model =
        firstAttemptModel(
            "Here is the revised prompt:\n```\nDo the thing carefully every time.\n```", null);
    var mutator = LlmReflectiveMutator.builder(model).build();
    var revised = mutator.propose("Parent prompt text x4 padding.", List.of());
    assertEquals("Do the thing carefully every time.", revised);
  }

  @Test
  void blankFirstAttemptTriggersStructuredRetry() {
    var model = firstBadThenStructuredModel("", "structured retry payload here.");
    var mutator = LlmReflectiveMutator.builder(model).build();
    var revised = mutator.propose("Parent prompt content here.", List.of());
    assertEquals("structured retry payload here.", revised);
  }

  @Test
  void tooShortFirstAttemptTriggersStructuredRetry() {
    var parent = "x".repeat(200);
    var model = firstBadThenStructuredModel("tiny", "the structured retry result text.");
    var mutator = LlmReflectiveMutator.builder(model).minLengthFraction(0.25).build();
    var revised = mutator.propose(parent, List.of());
    assertEquals("the structured retry result text.", revised);
  }

  @Test
  void bothAttemptsFailThrowsReflectionFailedException() {
    var model = bothFailModel("", "");
    var mutator = LlmReflectiveMutator.builder(model).build();
    assertThrows(
        ReflectionFailedException.class, () -> mutator.propose("parent prompt", List.of()));
  }

  @Test
  void retryRuntimeExceptionWrappedInReflectionFailed() {
    var model = retryThrowsModel("");
    var mutator = LlmReflectiveMutator.builder(model).build();
    assertThrows(
        ReflectionFailedException.class, () -> mutator.propose("parent prompt", List.of()));
  }

  @Test
  void proposeRejectsNullParent() {
    var mutator = LlmReflectiveMutator.builder(firstAttemptModel("x", null)).build();
    assertThrows(IllegalArgumentException.class, () -> mutator.propose(null, List.of()));
  }

  @Test
  void traceSamplerIsInvoked() {
    var sampler =
        TraceSampler.failuresFirst(1.0, 1, new Random(0)); // only failures + 1 success in prompt
    var seenPrompt = new AtomicReference<String>();
    var model =
        firstAttemptModel(
            "long enough revised prompt body matching the parent length requirements.", seenPrompt);
    var mutator = LlmReflectiveMutator.builder(model).traceSampler(sampler).build();
    mutator.propose(
        "Parent that's long enough.",
        List.of(
            new TraceFeedback("in1", "exp", "got", 0.0, "wrong 1", null),
            new TraceFeedback("in2", "exp", "got", 1.0, "ok", null),
            new TraceFeedback("in3", "exp", "got", 1.0, "ok", null)));
    var prompt = seenPrompt.get();
    assertTrue(prompt.contains("in1"));
    // Exactly 2 inputs in the rendered prompt: 1 failure + 1 success cap.
    var ins = prompt.split("input:");
    assertEquals(3, ins.length, "expected 2 trace 'input:' headers plus the preamble split");
  }

  @Test
  void builderMaxFeedbackCharsValidates() {
    var b = LlmReflectiveMutator.builder(firstAttemptModel("x", null));
    assertThrows(IllegalArgumentException.class, () -> b.maxFeedbackChars(-1));
  }

  @Test
  void builderMinLengthFractionValidates() {
    var b = LlmReflectiveMutator.builder(firstAttemptModel("x", null));
    assertThrows(IllegalArgumentException.class, () -> b.minLengthFraction(-0.1));
    assertThrows(IllegalArgumentException.class, () -> b.minLengthFraction(1.1));
  }

  @Test
  void reflectionInstructionsOverrideUsed() {
    var seenPrompt = new AtomicReference<String>();
    var model =
        firstAttemptModel(
            "long enough revised prompt body matching the parent length requirements.", seenPrompt);
    var mutator =
        LlmReflectiveMutator.builder(model).reflectionInstructions("MY CUSTOM HEADER").build();
    mutator.propose("Parent that's long enough.", List.of());
    assertTrue(seenPrompt.get().startsWith("MY CUSTOM HEADER"));
  }

  @Test
  void reflectionFailedExceptionHasMessageOnlyConstructor() {
    var ex = new ReflectionFailedException("oops");
    assertEquals("oops", ex.getMessage());
  }

  @Test
  void revisedPromptRecordRoundTrips() {
    var rp = new LlmReflectiveMutator.RevisedPrompt("hello");
    assertEquals("hello", rp.prompt());
  }
}
